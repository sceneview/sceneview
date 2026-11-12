@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sceneview.demo.demos

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.google.ar.core.*
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.arcore.hitTestDepth
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.ar.rememberHostLifecycle
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.DemoModalBottomSheet
import io.github.sceneview.demo.demos.internal.measure.*
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.math.Position
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.sample.rememberUnlitMaterialInstance
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
internal fun MeasureObjectModeSelector(objectMode: Boolean, onSelect: (Boolean) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        SingleChoiceSegmentedButtonRow(Modifier.padding(SceneViewTokens.Space.xs)) {
            listOf(false, true).forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = objectMode == mode,
                    onClick = { onSelect(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                    modifier = Modifier.testTag(if (mode) "measure_object_mode" else "measure_ruler_mode"),
                ) { Text(stringResource(if (mode) R.string.measure_mode_object else R.string.measure_mode_ruler)) }
            }
        }
    }
}

private val localSupport = BoxSupport(BoxPoint(0.0, 0.0, 0.0), BoxPoint(0.0, 1.0, 0.0))
private data class ObjectAim(val pose: Pose, val type: ObjectHitType, val distance: Double)

/** Points live in the support anchor's frame, so the wireframe follows refinements to that anchor. */
@Composable
internal fun ObjectMeasureScreen(onBack: () -> Unit, onRuler: () -> Unit) {
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val camera = rememberARCameraNode(engine)
    val context = LocalContext.current
    var support by remember { mutableStateOf<Anchor?>(null) }
    var supportTap by remember { mutableStateOf<ObjectTap?>(null) }
    var taps by remember { mutableStateOf<List<ObjectTap>>(emptyList()) }
    // ARCore Frame/HitResult are read only during the current frame or the next main-thread tap.
    val current = remember { arrayOfNulls<Frame>(1) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var session by remember { mutableStateOf<Session?>(null) }
    var aim by remember { mutableStateOf<ObjectAim?>(null) }
    var anchorPose by remember { mutableStateOf<Pose?>(null) }
    var lowestPlaneY by remember { mutableStateOf<Double?>(null) }
    var placement by remember { mutableStateOf(io.github.sceneview.demo.demos.internal.spatial.SpatialPlacement.TABLE) }
    var error by remember { mutableStateOf<Int?>(null) }
    var sheet by remember { mutableStateOf(false) }
    var showAnswer by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf(true) }
    val supportHolder = remember { arrayOfNulls<Anchor>(1) }
    SideEffect { supportHolder[0] = support }
    DisposableEffect(Unit) { onDispose { supportHolder[0]?.detach() } }

    fun reset() {
        support?.detach(); support = null; supportTap = null; anchorPose = null
        taps = emptyList(); error = null; sheet = false; showAnswer = false
    }
    fun undo() {
        if (taps.isNotEmpty()) taps = taps.dropLast(1) else reset()
        sheet = false; showAnswer = false; error = null
    }
    val box = remember(taps) {
        if (taps.size >= 3) ObjectBox.fromPoints(localSupport, taps[0].point, taps[1].point, taps[2].point, taps.getOrNull(3)?.point) else null
    }
    val uncertainty = if (taps.size == 4) MeasureUncertainty.estimate(supportTap!!, taps[0], taps[1], taps[2], taps[3]) else null
    val instruction = when {
        support == null -> R.string.measure_object_support
        taps.isEmpty() -> R.string.measure_object_length
        taps.size == 1 -> R.string.measure_object_remaining_corner
        taps.size == 2 -> R.string.measure_object_width
        taps.size == 3 -> R.string.measure_object_height
        else -> R.string.measure_object_review
    }

    fun addPoint() {
        val frame = current[0]
        if (frame == null || frame.camera.trackingState != TrackingState.TRACKING ||
            (support != null && support?.trackingState != TrackingState.TRACKING)) {
            error = R.string.measure_object_move; return
        }
        val hit = objectAim(frame, viewport.width / 2f, viewport.height / 2f, support == null, taps.size == 3)
        if (hit == null) { error = if (taps.size == 3) R.string.measure_object_no_top else R.string.measure_object_no_surface; return }
        if (support == null) {
            val anchor = runCatching { session?.createAnchor(hit.pose) }.getOrNull()
            if (anchor == null) { error = R.string.measure_object_move; return }
            support = anchor; anchorPose = anchor.pose
            supportTap = ObjectTap(hit.pose.boxPoint(), hit.type, hit.distance)
            // Compare with the lowest surface seen. Ambiguous supports near camera height default to TABLE.
            placement = objectPlacement(hit.pose.ty().toDouble(), lowestPlaneY?.takeIf { frame.camera.pose.ty() - hit.pose.ty() > 1.0f || it < hit.pose.ty() - 0.15 })
            error = null; return
        }
        val local = support!!.pose.inverse().compose(hit.pose).boxPoint()
        val next = taps + ObjectTap(local, hit.type, hit.distance)
        try {
            if (next.size == 2) ObjectBox.along(localSupport, next[0].point, next[1].point)
            if (next.size >= 3) ObjectBox.fromPoints(localSupport, next[0].point, next[1].point, next[2].point, next.getOrNull(3)?.point)
            taps = next; error = null
            if (next.size == 4) { sheet = true; showAnswer = false }
        } catch (e: ObjectBoxException) {
            error = when (e.reason) {
                ObjectBoxError.BELOW_SUPPORT -> R.string.measure_object_below
                ObjectBoxError.TOO_LARGE -> R.string.measure_object_too_large
                else -> R.string.measure_object_close_points
            }
        }
    }

    DemoScaffold(
        title = stringResource(R.string.demo_ar_measure_title), onBack = onBack,
        onReset = ::reset, arSessionFailed = failed, arOverlaysEnabled = available,
        topOverlay = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(SceneViewTokens.Space.md), verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm)) {
                    Text(stringResource(error ?: instruction), style = MaterialTheme.typography.bodyLarge,
                        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    if (box != null && uncertainty != null) ObjectBoxNumbers(box, uncertainty)
                }
            }
        },
        bottomOverlay = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(SceneViewTokens.Space.sm), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm)) {
                    MeasureObjectModeSelector(true) { if (!it) onRuler() }
                    Row(horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm)) {
                        OutlinedButton(onClick = ::undo, enabled = support != null) { Text(stringResource(R.string.measure_object_undo)) }
                        Button(onClick = { if (taps.size == 4) sheet = true else addPoint() }, enabled = available && !failed) {
                            Text(stringResource(if (taps.size == 4) R.string.measure_object_review else R.string.measure_object_tap))
                        }
                    }
                }
            }
        },
    ) {
        ARSceneView(
            modifier = Modifier.fillMaxSize().onSizeChanged { viewport = it },
            lifecycle = rememberHostLifecycle(), engine = engine, materialLoader = materialLoader, cameraNode = camera,
            planeRenderer = support == null,
            onSessionFailure = { failed = true }, onARCoreAvailability = { available = it == null },
            onSessionCreated = { session = it },
            sessionConfiguration = { created, config ->
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                config.depthMode = if (created.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
            },
            onSessionUpdated = { updated, frame ->
                current[0] = frame
                if (frame.camera.trackingState == TrackingState.TRACKING) {
                    updated.getAllTrackables(Plane::class.java).filter {
                        it.type == Plane.Type.HORIZONTAL_UPWARD_FACING && it.trackingState == TrackingState.TRACKING && it.subsumedBy == null
                    }.minOfOrNull { it.centerPose.ty().toDouble() }?.let {
                        if (lowestPlaneY == null || it < lowestPlaneY!!) lowestPlaneY = it
                    }
                    anchorPose = support?.takeIf { it.trackingState == TrackingState.TRACKING }?.pose
                    aim = if (!sheet && taps.size < 4) objectAim(frame, viewport.width / 2f, viewport.height / 2f, support == null, taps.size == 3) else null
                } else { aim = null }
            },
        ) {
            val material = rememberUnlitMaterialInstance(materialLoader, MaterialTheme.colorScheme.primary)
            val pose = anchorPose
            val candidate = aim?.let { pose?.inverse()?.compose(it.pose)?.boxPoint() }
            val preview = runCatching {
                when {
                    taps.size == 2 && candidate != null -> ObjectBox.fromPoints(localSupport, taps[0].point, taps[1].point, candidate)
                    taps.size == 3 && candidate != null -> ObjectBox.fromPoints(localSupport, taps[0].point, taps[1].point, taps[2].point, candidate)
                    else -> box
                }
            }.getOrNull() ?: box
            if (pose != null) {
                if (preview != null) {
                    val vertices = preview.vertices(localSupport.unitNormal).map { point -> pose.transformPoint(point.floatArray()).position() }
                    val edges = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0) +
                        if (preview.heightM > 0) listOf(4 to 5, 5 to 6, 6 to 7, 7 to 4, 0 to 4, 1 to 5, 2 to 6, 3 to 7) else emptyList()
                    edges.forEach { (a, b) -> key(a, b) { TubeNode(points = listOf(vertices[a], vertices[b]), radius = 0.002f, materialInstance = material) } }
                } else if (taps.isNotEmpty()) {
                    val end = taps.getOrNull(1)?.point ?: candidate?.let(localSupport::project)
                    if (end != null && (end - taps[0].point).length() >= ObjectBox.MIN_SIZE_M) {
                        TubeNode(points = listOf(taps[0].point, end).map { pose.transformPoint(localSupport.project(it).floatArray()).position() },
                            radius = 0.002f, materialInstance = material)
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(modifier = Modifier.clearAndSetSemantics {}, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text("+", Modifier.padding(SceneViewTokens.Space.xs), style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
    if (sheet && box != null && uncertainty != null) {
        val label = stringResource(R.string.measure_object_label)
        DemoModalBottomSheet(onDismissRequest = { sheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(SceneViewTokens.Space.md),
                verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.md)) {
                Text(label, style = MaterialTheme.typography.titleLarge)
                ObjectBoxNumbers(box, uncertainty)
                if (showAnswer) SpatialAnswer(box.toSpatialAnswer(placement, label), Modifier.fillMaxWidth())
                else Button(onClick = { showAnswer = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.measure_object_show_answer)) }
                OutlinedButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                        ClipData.newPlainText(label, MeasureBoxJson.encode(box, uncertainty)))
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.measure_object_copy)) }
                TextButton(onClick = ::undo) { Text(stringResource(R.string.measure_object_undo)) }
            }
        }
    }
}

@Composable
private fun ObjectBoxNumbers(box: ObjectBox, uncertainty: DimensionHalfWidths) {
    Text(stringResource(R.string.measure_object_result, (box.lengthM * 100).roundToInt(),
        (box.widthM * 100).roundToInt(), (box.heightM * 100).roundToInt(), uncertainty.displayEachCm),
        style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.measure_object_dimension_uncertainty, ceil(uncertainty.lengthCm).toInt(),
        ceil(uncertainty.widthCm).toInt(), ceil(uncertainty.heightCm).toInt()), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.measure_object_approximate), style = MaterialTheme.typography.bodyMedium)
}

/** Support: upward plane only. Top: depth first, then feature; never a plane fallback. */
private fun objectAim(frame: Frame, x: Float, y: Float, supportOnly: Boolean, topOnly: Boolean): ObjectAim? {
    val hits = runCatching { frame.hitTest(x, y) }.getOrDefault(emptyList())
        .filter { it.trackable.trackingState == TrackingState.TRACKING }
    fun HitResult.aim(type: ObjectHitType) = ObjectAim(hitPose, type, distance.toDouble())
    if (!topOnly) hits.firstOrNull {
        val plane = it.trackable as? Plane
        plane != null && plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING && plane.subsumedBy == null && plane.isPoseInPolygon(it.hitPose)
    }?.let { return it.aim(ObjectHitType.PLANE) }
    if (supportOnly) return null
    hits.firstOrNull { it.trackable is DepthPoint }?.let { return it.aim(ObjectHitType.DEPTH) }
    runCatching { frame.hitTestDepth(x, y) }.getOrNull()?.let {
        val p = it.position
        val pose = Pose.makeTranslation(p.x, p.y, p.z)
        return ObjectAim(pose, ObjectHitType.DEPTH, (pose.boxPoint() - frame.camera.pose.boxPoint()).length())
    }
    return hits.firstOrNull { it.trackable is Point }?.aim(ObjectHitType.FEATURE)
}

private fun Pose.boxPoint() = BoxPoint(tx().toDouble(), ty().toDouble(), tz().toDouble())
private fun BoxPoint.floatArray() = floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat())
private fun FloatArray.position() = Position(this[0], this[1], this[2])

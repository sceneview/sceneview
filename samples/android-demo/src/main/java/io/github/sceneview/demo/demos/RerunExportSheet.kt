@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.sceneview.demo.demos

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.ScatterPlot
import androidx.compose.material.icons.rounded.SquareFoot
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.FileProvider
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.DemoModalBottomSheet
import io.github.sceneview.demo.demos.internal.ArDebugOrbitCamera
import io.github.sceneview.demo.demos.internal.RerunCapturePack
import io.github.sceneview.demo.demos.internal.RerunExportAdapter
import io.github.sceneview.demo.demos.internal.RerunExportFormat
import io.github.sceneview.demo.demos.internal.RerunExportScene
import io.github.sceneview.demo.demos.internal.RerunExportSource
import io.github.sceneview.demo.demos.internal.RerunGlbWriter
import io.github.sceneview.demo.demos.internal.RerunPlyWriter
import io.github.sceneview.demo.demos.internal.RerunReplayAssets
import io.github.sceneview.demo.demos.internal.RerunResultView
import io.github.sceneview.demo.demos.internal.RerunRrdWriter
import io.github.sceneview.demo.demos.internal.RerunScanFile
import io.github.sceneview.demo.demos.internal.RerunScanModel
import io.github.sceneview.demo.demos.internal.PlanOffer
import io.github.sceneview.demo.demos.internal.formatFileSize
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.theme.SceneViewTokens.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/*
 * "Export scan": the scan's 3D model first — saved as a file or shared — then, folded under
 * "More formats", its raw material: the coloured points, the scan file itself, the Rerun
 * recording. Nothing is written until it is asked for: each file is prepared on its tap.
 */

/** The bundled sample's capture, straight from the assets. IO. */
internal fun sampleCapturePack(context: Context): RerunCapturePack {
    val assets = context.assets
    return RerunCapturePack(
        manifest = assets.open(RerunReplayAssets.MANIFEST).use { it.readBytes() },
        log = assets.open(RerunReplayAssets.LOG).use { it.readBytes() },
        media = assets.open(RerunReplayAssets.MEDIA).use { it.readBytes() },
    )
}

/**
 * Where one file stands: not asked for yet, being written, written, or failed. [Ready.note] and
 * [Failed.note] say what happened to the model's light copy ([sharedRerunModel]).
 */
private sealed interface Prepared {
    data object Preparing : Prepared
    data class Ready(val file: File, val note: RerunShareNote? = null) : Prepared
    data class Failed(val note: RerunShareNote = RerunShareNote.Failed) : Prepared
}

/**
 * The export sheet: the model's row with Save file and Share, a scan's floor plan when
 * [onFloorPlan] is given (#4540), then the other formats, folded. [floorPlan] says whether the
 * scan has a plan to open (#4714): without one the row stays, disabled, and says why.
 */
@Composable
@Suppress("LongMethod")
internal fun RerunExportSheet(
    source: RerunExportSource,
    onDismiss: () -> Unit,
    onFloorPlan: (() -> Unit)? = null,
    floorPlan: PlanOffer = PlanOffer.Ready,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files = remember(source) { RerunExportFiles(context.applicationContext, source) }
    val states = remember(source) { mutableStateMapOf<RerunExportFormat, Prepared>() }
    val model by produceState<RerunScanModel?>(null, source) {
        value = withContext(Dispatchers.IO) { runCatching { source.model?.invoke() }.getOrNull() }
    }
    val thumbnail by produceState<ImageBitmap?>(null, model) {
        val shown = model ?: return@produceState
        value = withContext(Dispatchers.Default) {
            runCatching {
                RerunResultView.thumbnail(shown, THUMBNAIL_PX, ArDebugOrbitCamera().verticalFovDegrees)?.let {
                    Bitmap.createBitmap(it, THUMBNAIL_PX, THUMBNAIL_PX, Bitmap.Config.ARGB_8888).asImageBitmap()
                }
            }.getOrNull()
        }
    }
    val formats = RerunExportFormat.available(hasModel = source.model != null, hasCapture = source.capture != null)
    val more = formats - RerunExportFormat.Model
    var expanded by remember(source) { mutableStateOf(false) }

    suspend fun prepare(format: RerunExportFormat): File? {
        (states[format] as? Prepared.Ready)?.let { return it.file }
        states[format] = Prepared.Preparing
        val written = runCatching { files.write(format) }
            .onFailure { if (it is CancellationException) throw it }
        states[format] = written.fold(
            onSuccess = { Prepared.Ready(it.first, it.second) },
            onFailure = { Prepared.Failed(RerunShareNote.of(it)) },
        )
        return written.getOrNull()?.first
    }
    var saving by remember { mutableStateOf<File?>(null) }
    val saved = stringResource(R.string.scan_export_saved)
    val failed = stringResource(R.string.scan_export_failed)
    val save = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(RerunExportFormat.Model.mimeType),
    ) { uri ->
        val file = saving
        saving = null
        if (uri == null || file == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { copyTo(context, file, uri) }.isSuccess }
            Toast.makeText(context, if (ok) saved else failed, Toast.LENGTH_SHORT).show()
        }
    }
    val share = { format: RerunExportFormat, title: String ->
        scope.launch { prepare(format)?.let { share(context, it, format, title) } }
        Unit
    }

    // The sheet follows the app theme, like the screen it opens over (#4080).
    DemoModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag(EXPORT_SHEET_TAG)) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = Space.lg)
                .padding(bottom = Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Text(
                text = stringResource(R.string.scan_export_title),
                style = Type.title,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            if (RerunExportFormat.Model in formats) {
                val modelTitle = stringResource(R.string.scan_export_model)
                ModelRow(
                    thumbnail = thumbnail,
                    // The file written once it is, lighter than the model on screen; until then, the model.
                    size = (states[RerunExportFormat.Model] as? Prepared.Ready)?.file?.length()
                        ?: model?.glb?.size?.toLong(),
                    state = states[RerunExportFormat.Model],
                    onRetry = { scope.launch { prepare(RerunExportFormat.Model) } },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Button(
                        onClick = {
                            scope.launch {
                                val file = prepare(RerunExportFormat.Model) ?: return@launch
                                saving = file
                                save.launch(file.name)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = SceneViewTokens.Layout.touchTarget)
                            .testTag(EXPORT_SAVE_TAG),
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null)
                        Text(
                            text = stringResource(R.string.scan_export_save),
                            style = Type.body.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(start = Space.sm),
                        )
                    }
                    OutlinedButton(
                        onClick = { share(RerunExportFormat.Model, modelTitle) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = SceneViewTokens.Layout.touchTarget)
                            .testTag(EXPORT_SHARE_TAG),
                    ) {
                        Icon(Icons.Outlined.Share, contentDescription = null)
                        Text(
                            text = stringResource(R.string.scan_export_share),
                            style = Type.body.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(start = Space.sm),
                        )
                    }
                }
            }
            if (onFloorPlan != null) {
                Column {
                    HorizontalDivider()
                    FloorPlanRow(offer = floorPlan, onClick = onFloorPlan)
                }
            }
            if (more.isNotEmpty()) {
                Column {
                    HorizontalDivider()
                    MoreHeader(expanded = expanded, onToggle = { expanded = !expanded })
                    AnimatedVisibility(visible = expanded) {
                        Column {
                            more.forEach { format ->
                                val title = stringResource(format.label)
                                FormatRow(
                                    format = format,
                                    title = title,
                                    state = states[format],
                                    onClick = { share(format, title) },
                                )
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Space.md),
                )
                Text(
                    text = stringResource(R.string.scan_export_privacy),
                    style = Type.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Space.sm),
                )
            }
        }
    }
}

/** The model: its picture, "3D model", "GLB · 1.2 MB", and what preparing it says. */
@Composable
private fun ModelRow(thumbnail: ImageBitmap?, size: Long?, state: Prepared?, onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag("$EXPORT_ROW_TAG_PREFIX${RerunExportFormat.Model.extension}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Box(
            modifier = Modifier
                .size(THUMBNAIL_SIZE)
                .clip(RoundedCornerShape(SceneViewTokens.Radius.md))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnail != null) {
                Image(bitmap = thumbnail, contentDescription = null, modifier = Modifier.size(THUMBNAIL_SIZE))
            } else {
                Icon(Icons.Rounded.ViewInAr, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(
                text = stringResource(R.string.scan_export_model),
                style = Type.body.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            val format = stringResource(R.string.scan_export_model_format)
            // "4.7 MB" in English like the rest of the sheet: the system formatter wrote "4,7 Mo" on a
            // French phone.
            Text(
                text = size?.let { "$format ·$NO_BREAK${formatFileSize(it).replace(' ', NO_BREAK)}" } ?: format,
                style = Type.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            (state as? Prepared.Ready)?.note?.let { note ->
                Text(
                    text = stringResource(note.message),
                    style = Type.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(EXPORT_MODEL_NOTE_TAG),
                )
            }
            PreparedLine(state = state, onRetry = onRetry)
        }
    }
}

/** "Preparing file…" while a file is written, "Couldn't create this file · Try again" when it failed. */
@Composable
private fun PreparedLine(state: Prepared?, onRetry: () -> Unit) {
    when (state) {
        Prepared.Preparing -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            CircularProgressIndicator(strokeWidth = Space.xs / 2, modifier = Modifier.size(Space.md))
            Text(
                text = stringResource(R.string.scan_export_preparing),
                style = Type.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is Prepared.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(state.note.message),
                style = Type.caption,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onRetry) { Text(stringResource(R.string.scan_export_retry)) }
        }
        else -> Unit
    }
}

/** "More formats", folded by default: the scan's raw material is for the few who want it. */
@Composable
private fun MoreHeader(expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SceneViewTokens.Layout.touchTarget)
            .clickable(role = Role.Button, onClick = onToggle)
            .testTag(EXPORT_MORE_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.scan_export_more),
            style = Type.body.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The scan's floor plan: a tap opens it, measured, with its PDF to share. */
@Composable
private fun FloorPlanRow(offer: PlanOffer, onClick: () -> Unit) {
    val ready = offer == PlanOffer.Ready
    // Without a plan the row is read, not tapped: muted, no chevron, the reason in its caption.
    val ink = if (ready) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SceneViewTokens.Layout.touchTarget)
            .clickable(enabled = ready, role = Role.Button, onClick = onClick)
            .padding(vertical = Space.xs)
            .testTag(EXPORT_FLOOR_PLAN_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Icon(
            imageVector = Icons.Rounded.SquareFoot,
            contentDescription = null,
            tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(SceneViewTokens.Layout.touchTarget).padding(Space.sm),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(
                text = stringResource(R.string.demo_ar_rerun_plan_export_title),
                style = Type.body.copy(fontWeight = FontWeight.SemiBold),
                color = ink,
            )
            Text(
                text = stringResource(
                    when (offer) {
                        PlanOffer.Ready -> R.string.demo_ar_rerun_plan_export_detail
                        PlanOffer.Measuring -> R.string.demo_ar_rerun_plan_measuring
                        PlanOffer.StillMeasuring -> R.string.demo_ar_rerun_plan_still_measuring
                        PlanOffer.NoDepth -> R.string.demo_ar_rerun_plan_export_no_depth
                        PlanOffer.NoWalls -> R.string.demo_ar_rerun_plan_export_no_walls
                        PlanOffer.Failed -> R.string.demo_ar_rerun_plan_export_failed
                    },
                ),
                style = Type.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (ready) {
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(SceneViewTokens.Layout.touchTarget).padding(Space.sm),
            )
        } else if (offer == PlanOffer.Measuring || offer == PlanOffer.StillMeasuring) {
            // The same small spinner as a format being prepared.
            CircularProgressIndicator(
                strokeWidth = Space.xs / 2,
                modifier = Modifier.padding(horizontal = Space.md).size(Space.md),
            )
        }
    }
}

/** One of the other formats: a tap prepares it, then opens the share sheet. */
@Composable
private fun FormatRow(format: RerunExportFormat, title: String, state: Prepared?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SceneViewTokens.Layout.touchTarget)
            .clickable(role = Role.Button, enabled = state != Prepared.Preparing, onClick = onClick)
            .padding(vertical = Space.xs)
            .testTag("$EXPORT_ROW_TAG_PREFIX${format.extension}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Icon(
            imageVector = format.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(SceneViewTokens.Layout.touchTarget).padding(Space.sm),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = Type.body, color = MaterialTheme.colorScheme.onSurface)
            PreparedLine(state = state, onRetry = onClick)
        }
        when (state) {
            Prepared.Preparing -> Unit
            is Prepared.Failed -> Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(SceneViewTokens.Layout.touchTarget).padding(Space.sm),
            )
            else -> Icon(
                Icons.Outlined.Share,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(SceneViewTokens.Layout.touchTarget).padding(Space.sm),
            )
        }
    }
}

private val RerunExportFormat.label: Int
    get() = when (this) {
        RerunExportFormat.Model -> R.string.scan_export_model
        RerunExportFormat.Points -> R.string.scan_export_points
        RerunExportFormat.Scan -> R.string.scan_export_scan
        RerunExportFormat.Recording -> R.string.scan_export_recording
    }

private val RerunExportFormat.icon: ImageVector
    get() = when (this) {
        RerunExportFormat.Model -> Icons.Rounded.ViewInAr
        RerunExportFormat.Points -> Icons.Rounded.ScatterPlot
        RerunExportFormat.Scan -> Icons.Rounded.Inventory2
        RerunExportFormat.Recording -> Icons.Rounded.Timeline
    }

/**
 * The files of one sheet, each written once, on its first ask, into a directory of its own under
 * the share root. The recording's scene, which the points, the recording and a scan without a
 * model all come from, is built once too.
 */
private class RerunExportFiles(private val context: Context, private val source: RerunExportSource) {
    private val lock = Mutex()
    private var dir: File? = null
    private var capture: RerunCapturePack? = null
    private var scene: RerunExportScene? = null

    /** [format]'s file, and — for the model — what there is to say about its light copy. */
    suspend fun write(format: RerunExportFormat): Pair<File, RerunShareNote?> = lock.withLock {
        var note: RerunShareNote? = null
        // Encoding (JPEG, glTF, zip) and simplifying are heavy: never on the main thread the sheet
        // launches from. Closing the sheet stops the simplification at its next progress step.
        val bytes = withContext(Dispatchers.Default) {
            val job = coroutineContext.job
            when (format) {
                RerunExportFormat.Model -> source.model?.invoke()
                    ?.let { model -> sharedRerunModel(model) { job.ensureActive() }.also { note = it.note }.glb }
                    ?: RerunGlbWriter.write(scene(), anchorModels(context, scene()), BitmapRerunImageCodec)
                RerunExportFormat.Points -> RerunPlyWriter.write(scene())
                RerunExportFormat.Scan ->
                    RerunScanFile.write(RerunScanFile.stamped(capture(), source.scope, source.title))
                RerunExportFormat.Recording -> RerunRrdWriter.write(scene(), BitmapRerunImageCodec)
            }
        }
        val file = withContext(Dispatchers.IO) {
            val into = dir ?: freshExportDirectory(context).also { dir = it }
            File(into, format.fileName(source.title)).apply { writeBytes(bytes) }
        }
        file to note
    }

    private suspend fun capture(): RerunCapturePack = capture
        ?: withContext(Dispatchers.IO) { checkNotNull(source.capture?.invoke()) { "no recording" } }
            .also { capture = it }

    private suspend fun scene(): RerunExportScene = scene
        ?: withContext(Dispatchers.Default) {
            checkNotNull(RerunExportAdapter.scene(capture(), source.title)) { "unreadable recording" }
        }.also { scene = it }
}

/** A fresh directory under the exports' own share directory; only the latest export is kept there. */
private fun freshExportDirectory(context: Context): File {
    val root = rerunShareDirectory(context, RERUN_SHARE_EXPORT)
    root.deleteRecursively()
    return File(root, UUID.randomUUID().toString()).apply { mkdirs() }
}

/** The model placed on the scene's anchors, when it has any: merged into the `.glb`. */
private fun anchorModels(context: Context, scene: RerunExportScene): Map<String, ByteArray> {
    if (scene.anchors.isEmpty()) return emptyMap()
    val name = RerunExportAdapter.ANCHOR_MODEL
    val bytes = runCatching { context.assets.open("models/$name.glb").use { it.readBytes() } }.getOrNull()
    return if (bytes == null) emptyMap() else mapOf(name to bytes)
}

/** [file] written where the file picker said. IO. */
private fun copyTo(context: Context, file: File, uri: Uri) {
    val out = checkNotNull(context.contentResolver.openOutputStream(uri)) { "no output for $uri" }
    out.use { stream -> file.inputStream().use { it.copyTo(stream) } }
}

/** [file] handed to the system's share sheet through the FileProvider. */
private fun share(context: Context, file: File, format: RerunExportFormat, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = format.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

internal const val EXPORT_SHEET_TAG = "ar_rerun_export_sheet"
internal const val EXPORT_SAVE_TAG = "ar_rerun_export_save"
internal const val EXPORT_SHARE_TAG = "ar_rerun_export_share"
internal const val EXPORT_MORE_TAG = "ar_rerun_export_more"
internal const val EXPORT_MODEL_NOTE_TAG = "ar_rerun_export_model_note"
internal const val EXPORT_FLOOR_PLAN_TAG = "ar_rerun_export_floor_plan"
internal const val EXPORT_ROW_TAG_PREFIX = "ar_rerun_export_"

/** The thumbnail's side on screen, and in pixels: drawn at about 3× for a sharp picture. */
private val THUMBNAIL_SIZE = SceneViewTokens.Layout.touchTarget + Space.lg
private const val THUMBNAIL_PX = 216

/** Keeps "819 kB" and its separator on one line when the detail wraps. */
private const val NO_BREAK = ' '

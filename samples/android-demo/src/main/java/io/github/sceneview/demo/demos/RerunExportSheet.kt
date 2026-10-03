@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.sceneview.demo.demos

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ScatterPlot
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.FileProvider
import io.github.sceneview.demo.common.DemoModalBottomSheet
import io.github.sceneview.demo.demos.internal.RerunCapturePack
import io.github.sceneview.demo.demos.internal.RerunExportAdapter
import io.github.sceneview.demo.demos.internal.RerunExportFormat
import io.github.sceneview.demo.demos.internal.RerunExportScene
import io.github.sceneview.demo.demos.internal.RerunExportSource
import io.github.sceneview.demo.demos.internal.RerunGlbWriter
import io.github.sceneview.demo.demos.internal.RerunPlyWriter
import io.github.sceneview.demo.demos.internal.RerunReplayAssets
import io.github.sceneview.demo.demos.internal.RerunRrdWriter
import io.github.sceneview.demo.demos.internal.formatFileSize
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.theme.SceneViewTokens.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/*
 * "Export your space": the session on screen written on the phone as the three open formats
 * Android can make — the Rerun recording, the glTF scene, the point cloud — each shared on its
 * own or all three together. The iOS demo's `RerunExportSheet`, minus USDZ.
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
 * The export sheet. It builds the scene once, then writes each format in turn off the main
 * thread, so the rows fill in one by one; a format that fails says so on its row.
 */
@Composable
internal fun RerunExportSheet(source: RerunExportSource, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val files = remember { mutableStateMapOf<RerunExportFormat, File>() }
    val failed = remember { mutableStateListOf<RerunExportFormat>() }
    LaunchedEffect(source) {
        val dir = withContext(Dispatchers.IO) { freshExportDirectory(context) }
        val scene = withContext(Dispatchers.Default) {
            runCatching { source.capture()?.let { RerunExportAdapter.scene(it, source.title) } }.getOrNull()
        }
        for (format in RerunExportFormat.entries) {
            val file = scene?.let {
                withContext(Dispatchers.IO) { runCatching { writeExport(context, it, format, dir) }.getOrNull() }
            }
            if (file != null) files[format] = file else failed += format
        }
    }
    val ready = RerunExportFormat.entries.mapNotNull { files[it] }
    // The sheet follows the app theme, like the replay it opens over (#4080).
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
                text = RerunExportFormat.SHEET_TITLE,
                style = Type.title,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Column {
                RerunExportFormat.entries.forEachIndexed { index, format ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = SceneViewTokens.Layout.touchTarget + Space.sm),
                        )
                    }
                    ExportRow(format, files[format], format in failed) { share(context, listOf(it), format.title) }
                }
            }
            val all = ready.size == RerunExportFormat.entries.size
            Button(
                onClick = { share(context, ready, source.title) },
                enabled = all,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SceneViewTokens.Layout.touchTarget)
                    .testTag(EXPORT_ALL_TAG),
            ) {
                Icon(Icons.Outlined.Share, contentDescription = null)
                Text(
                    text = if (all) RerunExportFormat.SHARE_ALL else RerunExportFormat.PREPARING,
                    style = Type.body.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(start = Space.sm),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Space.md),
                )
                Text(
                    text = RerunExportFormat.PRIVACY,
                    style = Type.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Space.sm),
                )
            }
        }
    }
}

/** One format: what it is, where it opens and its size, then Share once it is written. */
@Composable
private fun ExportRow(format: RerunExportFormat, file: File?, failed: Boolean, onShare: (File) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Space.xs),
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
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(
                    text = format.title,
                    style = Type.body.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = ".${format.extension}",
                    style = Type.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // "4.7 MB" in English like the rest of the sheet: the system formatter wrote "4,7 Mo" on a
            // French phone.
            val size = file?.let { formatFileSize(it.length()).replace(' ', NO_BREAK) }
            Text(
                text = if (size != null) "${format.detail} ·$NO_BREAK$size" else format.detail,
                style = Type.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        when {
            file != null -> IconButton(
                onClick = { onShare(file) },
                modifier = Modifier.testTag("$EXPORT_ROW_TAG_PREFIX${format.extension}"),
            ) {
                Icon(Icons.Outlined.Share, contentDescription = "Share the ${format.title} (.${format.extension})")
            }
            failed -> Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = RerunExportFormat.FAILED,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(SceneViewTokens.Layout.touchTarget).padding(Space.sm),
            )
            else -> CircularProgressIndicator(
                strokeWidth = Space.xs / 2,
                modifier = Modifier
                    .size(SceneViewTokens.Layout.touchTarget)
                    .padding(Space.sm + Space.xs)
                    .semantics { contentDescription = RerunExportFormat.PREPARING },
            )
        }
    }
}

private val RerunExportFormat.icon: ImageVector
    get() = when (this) {
        RerunExportFormat.Rrd -> Icons.Rounded.Timeline
        RerunExportFormat.Glb -> Icons.Rounded.ViewInAr
        RerunExportFormat.Ply -> Icons.Rounded.ScatterPlot
    }

/** A fresh directory under the exports' own share directory; only the latest export is kept there. */
private fun freshExportDirectory(context: Context): File {
    val root = rerunShareDirectory(context, RERUN_SHARE_EXPORT)
    root.deleteRecursively()
    return File(root, UUID.randomUUID().toString()).apply { mkdirs() }
}

/** [scene] written as [format] in [dir]. IO. */
private fun writeExport(context: Context, scene: RerunExportScene, format: RerunExportFormat, dir: File): File {
    val bytes = when (format) {
        RerunExportFormat.Rrd -> RerunRrdWriter.write(scene, BitmapRerunImageCodec)
        RerunExportFormat.Glb -> RerunGlbWriter.write(scene, anchorModels(context, scene), BitmapRerunImageCodec)
        RerunExportFormat.Ply -> RerunPlyWriter.write(scene)
    }
    return File(dir, format.fileName(scene.title)).apply { writeBytes(bytes) }
}

/** The model placed on the scene's anchors, when it has any: merged into the `.glb`. */
private fun anchorModels(context: Context, scene: RerunExportScene): Map<String, ByteArray> {
    if (scene.anchors.isEmpty()) return emptyMap()
    val name = RerunExportAdapter.ANCHOR_MODEL
    val bytes = runCatching { context.assets.open("models/$name.glb").use { it.readBytes() } }.getOrNull()
    return if (bytes == null) emptyMap() else mapOf(name to bytes)
}

/** [files] handed to the system's share sheet through the FileProvider. */
private fun share(context: Context, files: List<File>, title: String) {
    if (files.isEmpty()) return
    val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = RerunExportFormat.entries.firstOrNull { files[0].name.endsWith(".${it.extension}") }
                ?.mimeType ?: "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uris[0])
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
        }
    }
    intent.clipData = ClipData.newRawUri(files[0].name, uris[0]).apply {
        uris.drop(1).forEach { addItem(ClipData.Item(it)) }
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

internal const val EXPORT_SHEET_TAG = "ar_rerun_export_sheet"
internal const val EXPORT_ALL_TAG = "ar_rerun_export_all"
internal const val EXPORT_ROW_TAG_PREFIX = "ar_rerun_export_"

/** Keeps "819 kB" and its separator on one line when the detail wraps. */
private const val NO_BREAK = ' '

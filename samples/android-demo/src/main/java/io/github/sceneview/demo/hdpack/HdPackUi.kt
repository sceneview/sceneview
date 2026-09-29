package io.github.sceneview.demo.hdpack

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import io.github.sceneview.demo.R
import io.github.sceneview.demo.theme.SceneViewTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The process HD pack store, or `null` while it loads or when this build ships no readable
 * manifest. The first load reads an asset and scans a directory, so it runs on
 * [Dispatchers.IO] — never during composition on the main thread.
 */
@Composable
fun rememberHdPackStore(): HdPackStore? {
    val context = LocalContext.current
    val store by HdPack.loaded.collectAsState()
    LaunchedEffect(Unit) {
        if (HdPack.loaded.value == null) withContext(Dispatchers.IO) { HdPack.store(context) }
    }
    return store
}

/** Live status of [store]; [HdPackStatus.NotDownloaded] when there is no pack. */
@Composable
fun rememberHdPackStatus(store: HdPackStore?): State<HdPackStatus> {
    val context = LocalContext.current
    val flow = remember(store) {
        store?.let { HdPack.status(context, it) } ?: flowOf(HdPackStatus.NotDownloaded)
    }
    val initial = if (store?.isComplete == true) HdPackStatus.Ready else HdPackStatus.NotDownloaded
    return flow.collectAsState(initial = initial)
}

/** Live [HdPackStatus] of one model's file, for its viewer pill. `NotDownloaded` without an asset. */
@Composable
fun rememberHdAssetStatus(store: HdPackStore?, assetId: String?): State<HdPackStatus> {
    val context = LocalContext.current
    val flow = remember(store, assetId) {
        if (store != null && assetId != null) {
            HdPack.assetStatus(context, store, assetId)
        } else {
            flowOf(HdPackStatus.NotDownloaded)
        }
    }
    val onDisk = assetId != null && store?.readyFile(assetId) != null
    val initial = if (onDisk) HdPackStatus.Ready else HdPackStatus.NotDownloaded
    return flow.collectAsState(initial = initial)
}

/** "48 MB" — the platform's short file size, so it reads the way Settings → Storage does. */
fun hdPackSize(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

/**
 * "Download now": states the size, says whether it goes over mobile data, then queues the
 * download on any network. Nothing is fetched until [onConfirm].
 */
@Composable
fun HdPackDownloadDialog(totalBytes: Long, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val size = hdPackSize(context, totalBytes)
    // Only a network that is up and metered: `isActiveNetworkMetered` also answers `true` with no
    // network at all, which would warn about mobile data on a device that has none.
    val metered = remember {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
    }
    val body = stringResource(R.string.hd_pack_dialog_body, size)
    val meteredNote = stringResource(R.string.hd_pack_dialog_metered_note)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.HighQuality, contentDescription = null) },
        title = { Text(stringResource(R.string.hd_pack_dialog_title)) },
        text = { Text(if (metered) "$body $meteredNote" else body) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.hd_pack_dialog_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.hd_pack_dialog_cancel)) }
        },
    )
}

/**
 * About → App → "HD scenes · 48 MB" over "Downloaded". One row in the group's own anatomy
 * (leading glyph, label, trailing action), with the state as its supporting line and a progress
 * bar while a download runs. The action is "Download now" (a retry after a failure) until the
 * pack is complete, then "Remove". Copy shared word for word with the iOS demo.
 */
@Composable
fun HdPackSettingsRow() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val store = rememberHdPackStore() ?: return
    val status by rememberHdPackStatus(store)
    // Collected, not read with `.value`: the dialog's size follows each file that lands.
    val readyIds by store.readyIds.collectAsState()
    val scope = rememberCoroutineScope()
    var dialogOpen by remember { mutableStateOf(false) }
    val size = hdPackSize(context, store.manifest.totalBytes)
    val supporting = when (val s = status) {
        HdPackStatus.Ready -> stringResource(R.string.hd_pack_settings_ready)
        is HdPackStatus.Downloading ->
            stringResource(R.string.hd_pack_settings_downloading, (s.fraction * 100).toInt())
        HdPackStatus.Queued -> stringResource(R.string.hd_pack_settings_downloading, 0)
        HdPackStatus.WaitingForWifi -> stringResource(R.string.hd_pack_settings_waiting_wifi)
        HdPackStatus.WaitingForNetwork -> stringResource(R.string.hd_pack_settings_waiting_network)
        HdPackStatus.NotDownloaded -> stringResource(R.string.hd_pack_settings_not_downloaded)
        HdPackStatus.Failed -> stringResource(R.string.hd_pack_settings_failed)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SceneViewTokens.Layout.touchTarget)
            .padding(
                start = SceneViewTokens.Space.md,
                end = SceneViewTokens.Space.sm,
                top = SceneViewTokens.Space.sm,
                bottom = SceneViewTokens.Space.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.md),
    ) {
        Icon(
            imageVector = Icons.Outlined.HighQuality,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(SceneViewTokens.About.rowIcon),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
        ) {
            Text(
                text = stringResource(R.string.hd_pack_settings_title, size),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            (status as? HdPackStatus.Downloading)?.let { downloading ->
                LinearProgressIndicator(
                    progress = { downloading.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        when (status) {
            HdPackStatus.Ready -> TextButton(
                onClick = {
                    scope.launch {
                        val freed = HdPack.remove(context)
                        Toast.makeText(
                            context,
                            resources.getString(R.string.hd_pack_removed, hdPackSize(context, freed)),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                shape = RoundedCornerShape(SceneViewTokens.Radius.md),
            ) { Text(stringResource(R.string.hd_pack_action_remove), style = MaterialTheme.typography.labelLarge) }
            is HdPackStatus.Downloading -> Unit
            else -> TextButton(
                onClick = { dialogOpen = true },
                shape = RoundedCornerShape(SceneViewTokens.Radius.md),
            ) { Text(stringResource(R.string.hd_pack_action_download), style = MaterialTheme.typography.labelLarge) }
        }
    }
    if (dialogOpen) {
        HdPackDownloadDialog(
            totalBytes = store.manifest.missingBytes(readyIds),
            onConfirm = { dialogOpen = false; HdPack.downloadNow(context) },
            onDismiss = { dialogOpen = false },
        )
    }
}

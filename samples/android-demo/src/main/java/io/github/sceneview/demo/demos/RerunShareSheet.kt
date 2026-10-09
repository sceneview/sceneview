@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.DemoModalBottomSheet
import io.github.sceneview.demo.demos.internal.RerunStoredSession
import io.github.sceneview.demo.demos.internal.formatFileSize
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.theme.SceneViewTokens.Type
import kotlinx.coroutines.CancellationException

/**
 * What a shared scan contains, said before it leaves the phone: the photos of the room are in the
 * file unless the switch takes them out, and the size shown is the size of the very file Share
 * hands to Android. The copy is rebuilt off the main thread each time the switch moves; Share
 * stays off until the copy for the current choice is written. Closed without sending, the sheet
 * removes its copy.
 */
@Composable
internal fun RerunShareSheet(session: RerunStoredSession, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { rerunSessionStore(context) }
    // A scan recorded without photos has none to leave out: the switch is not offered, and the
    // sheet says what the file really holds.
    val offersPhotos = offersPhotosSwitch(session.photos)
    var includePhotos by remember(session.id) { mutableStateOf(offersPhotos) }
    var prepared by remember(session.id) { mutableStateOf<PreparedScan?>(null) }
    var failed by remember(session.id) { mutableStateOf(false) }
    LaunchedEffect(session.id, includePhotos) {
        // The cache holds one copy: the previous one is gone as soon as this one starts.
        prepared = null
        failed = false
        try {
            prepared = prepareSharedScan(context, store, session, includePhotos)
            failed = prepared == null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } catch (_: OutOfMemoryError) {
            // The copy is built in memory: a long scan on a small heap ends in the sheet's own
            // "could not be prepared" state, not in a crash.
            failed = true
        }
    }
    // A sheet closed without sending leaves no copy of the room in the cache, whichever way it
    // closes; a copy handed to Android stays until the next share, for the app that reads it.
    val sent = remember(session.id) { booleanArrayOf(false) }
    DisposableEffect(session.id) {
        onDispose { if (!sent[0]) discardSharedScan(context) }
    }
    // Only a copy written for the current switch position can be shared.
    val ready = prepared?.takeIf { it.includePhotos == includePhotos }
    DemoModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag(SHARE_SHEET_TAG)) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = Space.lg)
                .padding(bottom = Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Text(
                text = stringResource(R.string.room_scan_share),
                style = Type.title,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(
                    if (includePhotos) R.string.room_scan_share_privacy
                    else R.string.room_scan_share_privacy_without_photos,
                ),
                style = Type.body,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Column {
                if (offersPhotos) {
                    // The whole row is the switch: one target, one announcement.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = SceneViewTokens.Layout.touchTarget)
                            .toggleable(
                                value = includePhotos,
                                role = Role.Switch,
                                onValueChange = { includePhotos = it },
                            )
                            .testTag(SHARE_PHOTOS_TAG),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.md),
                    ) {
                        Text(
                            text = stringResource(R.string.room_scan_include_photos),
                            style = Type.body,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        // Off, M3 draws the border and the thumb in `outline`, 1.4:1 on the light
                        // sheet: `onSurfaceVariant` keeps the off switch above 3:1 in both themes.
                        Switch(
                            checked = includePhotos,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                uncheckedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = SceneViewTokens.Layout.touchTarget)
                        .semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    Text(
                        text = stringResource(R.string.room_scan_file_size),
                        style = Type.body,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = when {
                            ready != null -> formatFileSize(ready.bytes)
                            failed -> stringResource(R.string.room_scan_file_size_unknown)
                            else -> stringResource(R.string.room_scan_share_preparing)
                        },
                        style = Type.body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(SHARE_SIZE_TAG),
                    )
                }
            }
            if (failed) {
                Text(
                    text = stringResource(R.string.room_scan_share_failed),
                    style = Type.body,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Button(
                    onClick = {
                        // Read at the tap, not at the last composition: the file sent is the
                        // one written for the switch as it stands now.
                        prepared?.takeIf { it.includePhotos == includePhotos }?.let { copy ->
                            sent[0] = true
                            sharePreparedScan(context, copy.file, session.title)
                            onDismiss()
                        }
                    },
                    enabled = ready != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = SceneViewTokens.Layout.touchTarget)
                        .testTag(SHARE_SEND_TAG),
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null)
                    Text(
                        text = stringResource(R.string.room_scan_share),
                        style = Type.body.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(start = Space.sm),
                    )
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = SceneViewTokens.Layout.touchTarget),
                ) {
                    Text(
                        text = stringResource(R.string.room_scan_close),
                        style = Type.body.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }
    }
}

/**
 * Whether the sheet offers the `Include photos` switch: only a scan that holds photos has any to
 * leave out. Without them the sheet opens on the "no photos" wording, with no switch.
 */
internal fun offersPhotosSwitch(photos: Int): Boolean = photos > 0

internal const val SHARE_SHEET_TAG = "ar_rerun_share_sheet"
internal const val SHARE_PHOTOS_TAG = "ar_rerun_share_photos"
internal const val SHARE_SIZE_TAG = "ar_rerun_share_size"
internal const val SHARE_SEND_TAG = "ar_rerun_share_send"

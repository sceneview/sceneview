package io.github.sceneview.demo.feedback

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.R

/**
 * The lightweight "Report a bug" bottom sheet (#2188 successor).
 *
 * Zero permissions, zero services: a screenshot preview with an
 * include/exclude toggle, an optional description (with an optional voice
 * dictation shortcut, #3263), and two exits — a pre-filled GitHub issue in
 * the browser (the primary, default action) or the system share sheet
 * (text + screenshot attachment; the user picks Gmail / GitHub / anything —
 * text only for the GitHub path, since a URL cannot carry the image, and the
 * UI says so).
 *
 * On a successful hand-off the sheet dismisses itself and [onSent] fires
 * with a short confirmation message for the host to surface (e.g. a
 * snackbar) — there is no server-side GitHub API call here (no auth token
 * on device), so "success" means the browser/share intent launched, not
 * that an issue was actually filed; the confirmation copy reflects that.
 * When no app can handle the intent, the sheet stays open and shows the
 * error inline instead (#3263).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BugReportSheet(
    report: PendingBugReport,
    onDismiss: () -> Unit,
    onSent: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var note by remember { mutableStateOf("") }
    var includeScreenshot by remember { mutableStateOf(report.screenshot != null) }
    var sendError by remember { mutableStateOf<String?>(null) }

    val speechAvailable = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(context.packageManager) != null
    }
    val voicePrompt = stringResource(R.string.feedback_report_voice_prompt)
    val sentGitHubMessage = stringResource(R.string.feedback_report_sent_github)
    val sentShareMessage = stringResource(R.string.feedback_report_sent_share)
    val noAppError = stringResource(R.string.feedback_report_no_app)
    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
            if (spoken != null) {
                note = if (note.isBlank()) spoken else "$note $spoken"
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.BugReport,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.size(14.dp))
                Text(
                    stringResource(R.string.feedback_report_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.feedback_report_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            ScreenshotCard(
                screenshot = report.screenshot,
                included = includeScreenshot,
                onIncludedChange = { includeScreenshot = it },
            )

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(R.string.feedback_report_note_label)) },
                placeholder = { Text(stringResource(R.string.feedback_report_note_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                trailingIcon = if (speechAvailable) {
                    {
                        IconButton(
                            onClick = {
                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(
                                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                                    )
                                    putExtra(RecognizerIntent.EXTRA_PROMPT, voicePrompt)
                                }
                                runCatching { speechLauncher.launch(intent) }
                            },
                        ) {
                            Icon(
                                Icons.Outlined.Mic,
                                contentDescription = stringResource(R.string.feedback_report_voice_cd),
                            )
                        }
                    }
                } else {
                    null
                },
            )

            Spacer(Modifier.height(12.dp))
            PrivacyHint()

            Spacer(Modifier.height(16.dp))
            // GitHub first (#3263) — the primary, default destination.
            Button(
                onClick = {
                    if (openGitHubIssue(context, report.info, note)) {
                        sendError = null
                        onSent(sentGitHubMessage)
                        onDismiss()
                    } else {
                        sendError = noAppError
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(percent = 50),
            ) {
                Text(
                    stringResource(R.string.feedback_report_open_github),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            TextButton(
                onClick = {
                    if (shareReport(context, report, note, includeScreenshot)) {
                        sendError = null
                        onSent(sentShareMessage)
                        onDismiss()
                    } else {
                        sendError = noAppError
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.feedback_report_share))
            }
            if (sendError != null) {
                // Failure keeps the sheet open (#3263) — the error surfaces
                // here instead of a Toast so it stays visible until the user
                // tries again or dismisses.
                Text(
                    sendError.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
            if (report.screenshot != null) {
                // The GitHub path is text-only — a URL cannot embed an image.
                Text(
                    stringResource(R.string.feedback_report_screenshot_share_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ScreenshotCard(
    screenshot: CapturedScreenshot?,
    included: Boolean,
    onIncludedChange: (Boolean) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (screenshot == null) {
            Text(
                stringResource(R.string.feedback_report_screenshot_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(14.dp),
            )
            return@Surface
        }
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Image(
                        bitmap = screenshot.bitmap.asImageBitmap(),
                        contentDescription =
                            stringResource(R.string.feedback_report_screenshot_cd),
                        modifier = Modifier
                            .size(width = 56.dp, height = 96.dp),
                        contentScale = ContentScale.Crop,
                    )
                }
                Text(
                    stringResource(R.string.feedback_report_screenshot_include),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = included, onCheckedChange = onIncludedChange)
            }
            if (screenshot.mayMissSurfaceContent) {
                // Software-fallback capture — SurfaceView content (the 3D
                // viewport) may come out black on this device. Say so.
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.feedback_report_screenshot_may_be_black),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun PrivacyHint() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            stringResource(R.string.feedback_report_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Hand the report to the system share sheet: structured text as
 * `EXTRA_TEXT`, the screenshot (when included) as an `EXTRA_STREAM`
 * FileProvider attachment. The user picks the destination — nothing is
 * sent by the app itself.
 *
 * Returns `true` once the chooser has launched (the caller then dismisses
 * the sheet and shows a confirmation), `false` when no app can handle it
 * (the caller keeps the sheet open and shows the error inline, #3263).
 */
private fun shareReport(
    context: Context,
    report: PendingBugReport,
    note: String,
    includeScreenshot: Boolean,
): Boolean {
    val text = formatShareText(report.info, note)
    val title = formatReportTitle(report.info, note)
    val screenshotUri = report.screenshot
        ?.takeIf { includeScreenshot }
        ?.let { shot -> (context as? Activity)?.let { shot.contentUri(it) } }
    val send = Intent(Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
        if (screenshotUri != null) {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, screenshotUri)
            // ClipData + the grant flag: some share targets resolve the
            // stream through ClipData only, and the read grant must ride
            // the chooser to whichever app the user picks.
            clipData = ClipData.newUri(context.contentResolver, title, screenshotUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            type = "text/plain"
        }
    }
    return runCatching {
        context.startActivity(
            Intent.createChooser(
                send,
                context.getString(R.string.feedback_report_share_chooser),
            ),
        )
    }.isSuccess
}

/**
 * Open `issues/new` pre-filled with the report (title + markdown body).
 *
 * Returns `true` once the browser/GitHub app has launched, `false` when no
 * app can handle it — see [shareReport] for how the caller uses the result.
 */
private fun openGitHubIssue(context: Context, info: BugReportInfo, note: String): Boolean {
    val url = buildGitHubIssueUrl(info, note)
    return runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.isSuccess
}

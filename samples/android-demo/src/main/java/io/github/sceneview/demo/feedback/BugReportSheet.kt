package io.github.sceneview.demo.feedback

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.DemoModalBottomSheet
import io.github.sceneview.demo.common.VoiceDictationButton
import io.github.sceneview.demo.common.VoiceDictationListeningHint
import io.github.sceneview.demo.common.rememberVoiceDictation
import io.github.sceneview.demo.labs.Labs
import io.github.sceneview.demo.telemetry.logOutboundLink
import io.github.sceneview.demo.theme.SceneViewTokens
import kotlinx.coroutines.launch

/**
 * The lightweight "Report a bug" bottom sheet (#2188 successor).
 *
 * Zero permissions, zero services: a screenshot preview with an
 * include/exclude toggle, an optional description (with an optional voice
 * dictation shortcut, #3263), and two exits — a pre-filled GitHub issue in
 * the browser (the primary, default action) or the system share sheet
 * (a short summary, the whole report as a .txt file and the screenshot; the
 * user picks Gmail / GitHub / anything — text only for the GitHub path, since
 * a URL cannot carry the image, and the UI says so).
 *
 * On a successful hand-off the sheet simply dismisses itself — the browser
 * or share sheet opening over the app IS the visible confirmation, so no
 * extra snackbar/toast greets the user on their return (#3398). There is no
 * server-side GitHub API call here (no auth token on device), so "success"
 * means the browser/share intent launched, not that an issue was actually
 * filed. When no app can handle the intent, the sheet stays open and shows
 * the error inline instead (#3263).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BugReportSheet(
    report: PendingBugReport,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var note by remember { mutableStateOf("") }
    var includeScreenshot by remember { mutableStateOf(report.screenshot != null) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var previewExpanded by remember { mutableStateOf(false) }
    // Keyboard fix (#3322): a plain ModalBottomSheet does not consume the IME inset, so
    // the system keyboard used to cover the note field with no way to scroll it into
    // view — `imePadding()` below shrinks the sheet's content height as the keyboard
    // rises, and this requester nudges the scroll position the moment the field gains
    // focus, so the field (not just its top edge) lands above the keyboard on first tap.
    val noteFieldBringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()

    // Voice dictation (#4618): chains the system dialog through pauses, appending to the note.
    val dictation = rememberVoiceDictation(
        prompt = stringResource(R.string.feedback_report_voice_prompt),
        text = note,
        onTextChange = { note = it },
    )
    val noAppError = stringResource(R.string.feedback_report_no_app)

    DemoModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                // #3716: the container now reaches the true bottom edge — clear the
                // navigation bar explicitly instead of relying on the system inset.
                .navigationBarsPadding()
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
                minLines = 3,
                trailingIcon = if (dictation.available) {
                    {
                        VoiceDictationButton(
                            dictation = dictation,
                            idleContentDescription = stringResource(R.string.feedback_report_voice_cd),
                        )
                    }
                } else {
                    null
                },
                supportingText = if (dictation.listening) {
                    { VoiceDictationListeningHint() }
                } else {
                    null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(noteFieldBringIntoView)
                    .onFocusEvent { state ->
                        if (state.isFocused) {
                            scope.launch { noteFieldBringIntoView.bringIntoView() }
                        }
                    },
            )

            Spacer(Modifier.height(12.dp))
            AttachedContextHint(info = report.info)

            // #4526: what the attached file carries, masked, before anyone sends it.
            if (report.info.logcat.isNotEmpty() || report.info.arTracking.isNotEmpty()) {
                ReportPreview(
                    info = report.info,
                    note = note,
                    expanded = previewExpanded,
                    onExpandedChange = { previewExpanded = it },
                )
            }

            Spacer(Modifier.height(SceneViewTokens.Space.sm))
            PrivacyHint()

            Spacer(Modifier.height(16.dp))
            // GitHub first (#3263) — the primary, default destination.
            Button(
                onClick = {
                    if (openGitHubIssue(context, report.info, note)) {
                        sendError = null
                        // The first report sent joins SceneView Labs (see Labs.onReportSent).
                        Labs.onReportSent()
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
                        Labs.onReportSent()
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

/**
 * "Attached: Showcase tab · 1200 log lines" — the two things the report used to
 * be missing, shown before it is sent (#3390).
 *
 * Worth the row: the sheet promises the report bundles context, and this is
 * where the user (and a maintainer reading a screenshot of this sheet) can see
 * *which* screen was captured and how deep the log actually goes.
 */
@Composable
private fun AttachedContextHint(info: BugReportInfo) {
    val screen = info.metadata["Screen"] ?: return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            stringResource(
                R.string.feedback_report_attached,
                screen,
                pluralStringResource(
                    R.plurals.feedback_report_log_lines,
                    info.logcat.size,
                    info.logcat.size,
                ),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The attached report as it will leave the device, folded by default (#4526):
 * paths, URIs, e-mail addresses and picked file names already read
 * `<redacted …>` here, so the person sending it can check that for themselves.
 */
@Composable
private fun ReportPreview(
    info: BugReportInfo,
    note: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(
            onClick = { onExpandedChange(!expanded) },
            contentPadding = PaddingValues(horizontal = 0.dp),
        ) {
            Text(
                stringResource(
                    if (expanded) R.string.feedback_report_preview_hide else R.string.feedback_report_preview_show,
                ),
            )
        }
        if (expanded) {
            // Built only while unfolded: the whole log tail, rebuilt as the note changes.
            val text = remember(info, note) { formatReportFile(info, note) }
            Surface(
                shape = RoundedCornerShape(SceneViewTokens.Radius.sm),
                // Highest, not the screenshot card's tone: a code block reads as one against the sheet.
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp),
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(SceneViewTokens.Space.md),
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
 * Hand the report to the system share sheet: a short summary as `EXTRA_TEXT`,
 * the whole report with the log as a `.txt` FileProvider attachment, and the
 * screenshot (when included) as a second one. The user picks the destination —
 * nothing is sent by the app itself.
 *
 * The log never rides in the intent itself (#4528): 1200 lines in `EXTRA_TEXT`
 * made a ~600 KB Binder transaction, and the chooser was refused on the first tap
 * with nothing on screen. A `content://` URI weighs a few bytes.
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
    val title = formatReportTitle(report.info, note)
    val authority = "${context.packageName}.fileprovider"
    val reportUri = writeReportFile(context, report.info, note)?.let { file ->
        runCatching { FileProvider.getUriForFile(context, authority, file) }.getOrNull()
    }
    val text = formatShareText(report.info, note, logAttached = reportUri != null)
    val screenshotUri = report.screenshot
        ?.takeIf { includeScreenshot }
        ?.let { shot -> (context as? Activity)?.let { shot.contentUri(it) } }
    val attachments = listOfNotNull(reportUri, screenshotUri)
    val send = when {
        attachments.size > 1 -> Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(attachments))
        }
        attachments.size == 1 -> Intent(Intent.ACTION_SEND).apply {
            type = if (attachments[0] == screenshotUri) "image/png" else "text/plain"
            putExtra(Intent.EXTRA_STREAM, attachments[0])
        }
        else -> Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
    }.apply {
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
        if (attachments.isNotEmpty()) {
            // ClipData + the grant flag: some share targets resolve the
            // stream through ClipData only, and the read grant must ride
            // the chooser to whichever app the user picks.
            clipData = ClipData.newUri(context.contentResolver, title, attachments[0]).also { clip ->
                attachments.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
    logOutboundLink(url)
    return runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.isSuccess
}

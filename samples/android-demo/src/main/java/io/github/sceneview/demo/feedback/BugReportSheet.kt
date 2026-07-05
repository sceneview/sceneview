package io.github.sceneview.demo.feedback

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
 * include/exclude toggle, an optional description, and two exits —
 * the system share sheet (text + screenshot attachment; the user picks
 * Gmail / GitHub / anything) or a pre-filled GitHub issue in the browser
 * (text only — a URL cannot carry the image, and the UI says so).
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
            )

            Spacer(Modifier.height(12.dp))
            PrivacyHint()

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    shareReport(
                        context = context,
                        report = report,
                        note = note,
                        includeScreenshot = includeScreenshot,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(percent = 50),
            ) {
                Text(
                    stringResource(R.string.feedback_report_share),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            TextButton(
                onClick = { openGitHubIssue(context, report.info, note) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.feedback_report_open_github))
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
 */
private fun shareReport(
    context: Context,
    report: PendingBugReport,
    note: String,
    includeScreenshot: Boolean,
) {
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
    runCatching {
        context.startActivity(
            Intent.createChooser(
                send,
                context.getString(R.string.feedback_report_share_chooser),
            ),
        )
    }.onFailure {
        Toast.makeText(context, R.string.feedback_report_no_app, Toast.LENGTH_LONG).show()
    }
}

/** Open `issues/new` pre-filled with the report (title + markdown body). */
private fun openGitHubIssue(context: Context, info: BugReportInfo, note: String) {
    val url = buildGitHubIssueUrl(info, note)
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        Toast.makeText(context, R.string.feedback_report_no_app, Toast.LENGTH_LONG).show()
    }
}

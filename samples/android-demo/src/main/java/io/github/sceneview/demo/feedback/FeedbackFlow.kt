package io.github.sceneview.demo.feedback

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.sceneview.demo.R

private enum class FeedbackStep { ONBOARDING, CATEGORY, CONSENT, REVIEW, SENT }

/**
 * The in-app feedback flow, shown as a full-screen dialog: one-time onboarding,
 * the Bug/Idea picker, the screen + microphone consent screen, then — after the
 * user has recorded — a review screen.
 *
 * The recording itself happens with the dialog dismissed (so the user can
 * demonstrate the bug); [FeedbackRecorder] bridges the recording state back in,
 * and this flow shows the review step once a recording is available.
 *
 * Sending the feedback is wired in task 1D (#1934) — for now "Send" leads to a
 * placeholder confirmation.
 *
 * @param onStartRecording requests the screen + mic permissions and starts the
 *   recording service; see [rememberFeedbackRecordingLauncher].
 */
@Composable
fun FeedbackFlow(onDismiss: () -> Unit, onStartRecording: () -> Unit) {
    val context = LocalContext.current
    val recState by FeedbackRecorder.state.collectAsState()

    var step by rememberSaveable {
        mutableStateOf(
            if (FeedbackPrefs.hasSeenOnboarding(context)) FeedbackStep.CATEGORY
            else FeedbackStep.ONBOARDING,
        )
    }
    var category by rememberSaveable { mutableStateOf<FeedbackCategory?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            val doneRecording = (recState as? RecordingState.Done)?.recording
            when {
                recState is RecordingState.Failed -> FailedStep(
                    onClose = onDismiss,
                    onRetry = {
                        FeedbackRecorder.reset()
                        onStartRecording()
                    },
                )

                doneRecording != null || step == FeedbackStep.REVIEW -> ReviewStep(
                    recording = doneRecording,
                    onClose = onDismiss,
                    onRerecord = {
                        FeedbackRecorder.reset()
                        onStartRecording()
                    },
                    onSend = {
                        // Upload is wired in task 1D — for now discard the
                        // local recording and show a placeholder confirmation.
                        FeedbackRecorder.reset()
                        step = FeedbackStep.SENT
                    },
                )

                else -> when (step) {
                    FeedbackStep.ONBOARDING -> OnboardingStep(
                        onClose = onDismiss,
                        onContinue = {
                            FeedbackPrefs.markOnboardingSeen(context)
                            step = FeedbackStep.CATEGORY
                        },
                    )
                    FeedbackStep.CATEGORY -> CategoryStep(
                        onClose = onDismiss,
                        onPick = {
                            category = it
                            step = FeedbackStep.CONSENT
                        },
                    )
                    FeedbackStep.CONSENT -> ConsentStep(
                        category = category ?: FeedbackCategory.BUG,
                        onClose = onDismiss,
                        onBack = { step = FeedbackStep.CATEGORY },
                        onAgree = {
                            FeedbackRecorder.category = category
                            onStartRecording()
                        },
                        onSkip = {
                            FeedbackRecorder.category = category
                            step = FeedbackStep.REVIEW
                        },
                    )
                    FeedbackStep.SENT -> SentStep(onClose = onDismiss)
                    FeedbackStep.REVIEW -> Unit // handled above
                }
            }
        }
    }
}

// ── Steps ────────────────────────────────────────────────────────────────────

@Composable
private fun OnboardingStep(onClose: () -> Unit, onContinue: () -> Unit) {
    FeedbackStepScaffold(
        onClose = onClose,
        actions = {
            PrimaryButton(
                text = stringResource(R.string.feedback_onboarding_continue),
                onClick = onContinue,
            )
        },
    ) {
        Spacer(Modifier.height(16.dp))
        FeedbackHeroIcon(Icons.Outlined.Feedback, Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(24.dp))
        StepTitle(stringResource(R.string.feedback_onboarding_title))
        Spacer(Modifier.height(12.dp))
        StepBody(stringResource(R.string.feedback_onboarding_body))
    }
}

@Composable
private fun CategoryStep(onClose: () -> Unit, onPick: (FeedbackCategory) -> Unit) {
    FeedbackStepScaffold(onClose = onClose) {
        Spacer(Modifier.height(8.dp))
        StepTitle(stringResource(R.string.feedback_category_title))
        Spacer(Modifier.height(24.dp))
        CategoryCard(
            icon = Icons.Outlined.BugReport,
            tint = MaterialTheme.colorScheme.error,
            title = stringResource(R.string.feedback_bug_title),
            subtitle = stringResource(R.string.feedback_bug_subtitle),
            onClick = { onPick(FeedbackCategory.BUG) },
        )
        Spacer(Modifier.height(12.dp))
        CategoryCard(
            icon = Icons.Outlined.Lightbulb,
            tint = MaterialTheme.colorScheme.tertiary,
            title = stringResource(R.string.feedback_idea_title),
            subtitle = stringResource(R.string.feedback_idea_subtitle),
            onClick = { onPick(FeedbackCategory.IDEA) },
        )
    }
}

@Composable
private fun ConsentStep(
    category: FeedbackCategory,
    onClose: () -> Unit,
    onBack: () -> Unit,
    onAgree: () -> Unit,
    onSkip: () -> Unit,
) {
    FeedbackStepScaffold(
        onClose = onClose,
        actions = {
            PrimaryButton(
                text = stringResource(R.string.feedback_consent_agree),
                onClick = onAgree,
            )
            if (category == FeedbackCategory.IDEA) {
                TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.feedback_consent_skip))
                }
            }
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.feedback_consent_back))
            }
        },
    ) {
        Spacer(Modifier.height(16.dp))
        FeedbackHeroIcon(Icons.Outlined.Videocam, Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(24.dp))
        StepTitle(stringResource(R.string.feedback_consent_title))
        Spacer(Modifier.height(12.dp))
        StepBody(stringResource(R.string.feedback_consent_body))
        Spacer(Modifier.height(16.dp))
        ConsentBullet(Icons.Outlined.Smartphone, stringResource(R.string.feedback_consent_screen))
        Spacer(Modifier.height(10.dp))
        ConsentBullet(Icons.Outlined.Mic, stringResource(R.string.feedback_consent_voice))
        if (category == FeedbackCategory.IDEA) {
            Spacer(Modifier.height(10.dp))
            ConsentBullet(
                Icons.Outlined.Lightbulb,
                stringResource(R.string.feedback_consent_idea_optional),
            )
        }
        Spacer(Modifier.height(16.dp))
        PrivacyNote()
    }
}

@Composable
private fun ReviewStep(
    recording: FeedbackRecording?,
    onClose: () -> Unit,
    onRerecord: () -> Unit,
    onSend: (note: String) -> Unit,
) {
    var note by rememberSaveable { mutableStateOf("") }
    FeedbackStepScaffold(
        onClose = onClose,
        actions = {
            PrimaryButton(
                text = stringResource(R.string.feedback_review_send),
                onClick = { onSend(note.trim()) },
            )
            TextButton(onClick = onRerecord, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.feedback_review_rerecord))
            }
        },
    ) {
        Spacer(Modifier.height(16.dp))
        FeedbackHeroIcon(Icons.Outlined.CheckCircle, Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(24.dp))
        StepTitle(stringResource(R.string.feedback_review_title))
        Spacer(Modifier.height(12.dp))
        StepBody(stringResource(R.string.feedback_review_body))
        Spacer(Modifier.height(16.dp))
        if (recording != null) {
            RecordingSummary(recording)
        } else {
            StepBody(stringResource(R.string.feedback_review_no_recording))
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text(stringResource(R.string.feedback_review_note_label)) },
            placeholder = { Text(stringResource(R.string.feedback_review_note_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )
    }
}

@Composable
private fun FailedStep(onClose: () -> Unit, onRetry: () -> Unit) {
    FeedbackStepScaffold(
        onClose = onClose,
        actions = {
            PrimaryButton(
                text = stringResource(R.string.feedback_record_retry),
                onClick = onRetry,
            )
        },
    ) {
        Spacer(Modifier.height(16.dp))
        FeedbackHeroIcon(Icons.Outlined.ErrorOutline, Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(24.dp))
        StepTitle(stringResource(R.string.feedback_record_failed_title))
        Spacer(Modifier.height(12.dp))
        StepBody(stringResource(R.string.feedback_record_failed_body))
    }
}

@Composable
private fun SentStep(onClose: () -> Unit) {
    FeedbackStepScaffold(
        onClose = onClose,
        actions = {
            PrimaryButton(
                text = stringResource(R.string.feedback_stub_done),
                onClick = onClose,
            )
        },
    ) {
        Spacer(Modifier.height(16.dp))
        FeedbackHeroIcon(Icons.Outlined.CheckCircle, Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(24.dp))
        StepTitle(stringResource(R.string.feedback_stub_title))
        Spacer(Modifier.height(12.dp))
        StepBody(stringResource(R.string.feedback_stub_body))
    }
}

// ── Shared pieces ────────────────────────────────────────────────────────────

/** Close row, scrolling content area, and a pinned action area at the bottom. */
@Composable
private fun FeedbackStepScaffold(
    onClose: () -> Unit,
    actions: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.feedback_cd_close),
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            content = content,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = actions,
        )
    }
}

@Composable
private fun ColumnScope.PrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(percent = 50),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun StepBody(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FeedbackHeroIcon(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(72.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(34.dp),
        )
    }
}

@Composable
private fun CategoryCard(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(tint.copy(alpha = 0.18f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConsentBullet(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PrivacyNote() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                stringResource(R.string.feedback_consent_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecordingSummary(recording: FeedbackRecording) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Outlined.Videocam,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.feedback_review_recorded),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    formatDuration(recording.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

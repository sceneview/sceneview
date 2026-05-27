package io.github.sceneview.demo.feedback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.sceneview.demo.R
import kotlinx.coroutines.launch

private enum class FeedbackStep { ONBOARDING, CATEGORY, CONSENT, REVIEW, SENDING, TICKETS }

/** State of the feedback upload, driving the SENDING step. */
private sealed interface UploadUiState {
    /**
     * Upload in flight. [progress] is the fraction of the payload sent
     * (`0f..1f`), or `null` while the request is still being prepared / the
     * worker is processing — the UI then shows an indeterminate spinner.
     */
    data class Sending(val progress: Float? = null) : UploadUiState
    data class Sent(val issueNumber: Int?) : UploadUiState
    data class Failed(val errorKind: FeedbackUploader.ErrorKind) : UploadUiState
}

/**
 * The in-app feedback flow, shown as a full-screen dialog: one-time onboarding,
 * the Bug/Idea picker, the screen + microphone consent screen, a review screen,
 * and the upload to the SceneView feedback worker.
 *
 * The recording itself happens with the dialog dismissed (so the user can
 * demonstrate the bug); [FeedbackRecorder] bridges the recording state back in.
 *
 * @param launcher requests the screen + mic permissions and starts the
 *   recording service; see [rememberFeedbackRecordingLauncher]. Also exposes
 *   an [FeedbackRecordingLauncher.openAppSettings] path for a permanently-denied
 *   microphone permission.
 * @param micPermanentlyDenied `true` when the microphone permission is
 *   "Don't ask again"-denied — the flow then routes the user to app settings
 *   instead of looping a no-op "Retry".
 * @param notificationControlAvailable `false` when the foreground-service
 *   notification (a documented stop affordance) will be silent — the flow then
 *   surfaces a one-time hint that the in-app Stop pill is the way to finish.
 * @param recordingAvailable `false` when screen recording is unavailable on
 *   this build (e.g. the manifest omits `FOREGROUND_SERVICE_MEDIA_PROJECTION`
 *   on Android 14+ — the catch-22 from #2120 / #2188). The flow then skips the
 *   consent step entirely and goes straight from CATEGORY to REVIEW so the
 *   user can submit a text-only note. Without this, attempting to start the
 *   foreground service crashes the app with
 *   `ForegroundServiceDidNotStartInTimeException`.
 */
@Composable
fun FeedbackFlow(
    onDismiss: () -> Unit,
    launcher: FeedbackRecordingLauncher,
    micPermanentlyDenied: Boolean = false,
    notificationControlAvailable: Boolean = true,
    recordingAvailable: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recState by FeedbackRecorder.state.collectAsState()
    val onStartRecording: () -> Unit = launcher.startRecording

    var step by rememberSaveable {
        mutableStateOf(
            if (FeedbackPrefs.hasSeenOnboarding(context)) FeedbackStep.CATEGORY
            else FeedbackStep.ONBOARDING,
        )
    }
    var category by rememberSaveable {
        // Restore the in-flight category if the process was killed during the
        // MediaProjection consent dialog — otherwise an "Idea" silently becomes
        // a "Bug" on the way back (#1930 review).
        mutableStateOf(FeedbackRecorder.category ?: FeedbackCategoryStore.load(context))
    }
    var note by rememberSaveable { mutableStateOf("") }
    // uploadState is rememberSaveable-friendly only as an enum-ish marker: the
    // actual upload coroutine cannot survive a process death. On restore, if
    // `step` was SENDING but there is no live upload, we drop back to REVIEW so
    // the user is never stranded on a spinner with no way out (handled below).
    var uploadState by remember { mutableStateOf<UploadUiState>(UploadUiState.Sending()) }
    // True once the user actually launched an upload coroutine in THIS
    // composition — distinguishes "genuinely sending" from "step==SENDING was
    // restored after process death".
    var uploadLaunched by remember { mutableStateOf(false) }

    // Recover from a process death mid-send: `step` is rememberSaveable, the
    // upload coroutine is not. Without this the user is stuck on the SENDING
    // spinner forever (#1930 review).
    LaunchedEffect(Unit) {
        if (step == FeedbackStep.SENDING && !uploadLaunched) {
            step = FeedbackStep.REVIEW
        }
    }

    // The previously submitted tickets — re-read whenever the flow shows the
    // category or tickets step, so a just-submitted ticket appears without
    // having to close and re-open the dialog (#1930 review).
    var ticketsRefresh by remember { mutableStateOf(0) }
    val tickets = remember(ticketsRefresh) { SubmittedFeedbackStore.all(context) }
    LaunchedEffect(step) {
        if (step == FeedbackStep.CATEGORY || step == FeedbackStep.TICKETS) {
            ticketsRefresh++
        }
    }

    fun send() {
        val recording = (FeedbackRecorder.state.value as? RecordingState.Done)?.recording
        val chosen = FeedbackRecorder.category ?: category ?: FeedbackCategory.BUG
        val text = note.trim()
        // Capture the context — including the current demo id (#1934) —
        // before switching to the SENDING step.
        val feedbackContext = captureFeedbackContext(context)
        uploadState = UploadUiState.Sending()
        uploadLaunched = true
        step = FeedbackStep.SENDING
        scope.launch {
            // Mark the recording media as held so a dialog dismiss during
            // SENDING cannot delete the file out from under the upload read.
            if (recording != null) FeedbackRecorder.beginUpload()
            try {
                val result = FeedbackUploader.upload(
                    category = chosen,
                    note = text,
                    context = feedbackContext,
                    recording = recording,
                    onProgress = { fraction ->
                        // Keep showing the determinate bar only while still
                        // uploading; once at 100% the worker is processing, so
                        // fall back to the indeterminate spinner.
                        val current = uploadState
                        if (current is UploadUiState.Sending) {
                            uploadState = UploadUiState.Sending(
                                progress = fraction.takeIf { it < 1f },
                            )
                        }
                    },
                )
                uploadState = if (result.ok) {
                    UploadUiState.Sent(result.issueNumber)
                } else {
                    UploadUiState.Failed(result.errorKind)
                }
                if (result.ok) {
                    // Index the new ticket locally so "My feedback" can track
                    // it. Skipped when the worker stored the feedback but
                    // opened no issue (e.g. its hourly issue quota was hit) —
                    // there is then nothing to link to.
                    if (result.issueNumber != null && result.issueUrl != null) {
                        SubmittedFeedbackStore.add(
                            context,
                            SubmittedFeedback(
                                feedbackId = result.feedbackId ?: "",
                                issueNumber = result.issueNumber,
                                issueUrl = result.issueUrl,
                                category = chosen,
                                title = text,
                                createdAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                    FeedbackRecorder.reset()
                    FeedbackCategoryStore.save(context, null)
                }
            } finally {
                // Release the media: deletes the files if a reset() landed
                // while the upload was in flight.
                if (recording != null) FeedbackRecorder.endUpload()
            }
        }
    }

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
            // Set the in-flight category on the process-wide recorder AND
            // mirror it to prefs, so it survives a process death during the
            // MediaProjection consent dialog (#1930 review).
            fun rememberCategory(value: FeedbackCategory?) {
                FeedbackRecorder.category = value
                FeedbackCategoryStore.save(context, value)
            }
            when {
                step == FeedbackStep.SENDING -> SendingStep(
                    state = uploadState,
                    onClose = onDismiss,
                    onRetry = { send() },
                )

                recState is RecordingState.Failed &&
                    (recState as RecordingState.Failed).reason ==
                        FeedbackRecorder.FAILURE_RECORDING_UNAVAILABLE -> {
                    // Screen recording is unavailable on this build (the #2120
                    // catch-22). Drop the failure silently and route the user
                    // straight to the text-only REVIEW — surfacing a generic
                    // "recording didn't work" screen would be misleading
                    // because no attempt was even made. The user keeps the
                    // category they picked.
                    LaunchedEffect(Unit) {
                        FeedbackRecorder.reset()
                        step = FeedbackStep.REVIEW
                    }
                }

                recState is RecordingState.Failed -> FailedStep(
                    micPermanentlyDenied = micPermanentlyDenied,
                    projectionRevoked =
                        (recState as RecordingState.Failed).reason ==
                            FeedbackRecorder.FAILURE_REVOKED,
                    onClose = onDismiss,
                    onRetry = {
                        FeedbackRecorder.reset()
                        onStartRecording()
                    },
                    onOpenSettings = launcher.openAppSettings,
                    onSkipRecording = {
                        // Drop the failed recording state and continue with a
                        // text-only submission rather than dead-ending.
                        FeedbackRecorder.reset()
                        step = FeedbackStep.REVIEW
                    },
                )

                doneRecording != null || step == FeedbackStep.REVIEW -> ReviewStep(
                    recording = doneRecording,
                    note = note,
                    onNoteChange = { note = it },
                    onClose = onDismiss,
                    onRerecord = {
                        FeedbackRecorder.reset()
                        onStartRecording()
                    },
                    onSend = { send() },
                    emulator = isEmulator(),
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
                            // When screen recording is unavailable on this
                            // build (the #2120 / #2188 catch-22), skip the
                            // consent step — it advertises screen + mic
                            // capture that cannot actually happen — and go
                            // straight to a text-only review.
                            step = if (recordingAvailable) {
                                FeedbackStep.CONSENT
                            } else {
                                rememberCategory(category)
                                FeedbackStep.REVIEW
                            }
                        },
                        hasTickets = tickets.isNotEmpty(),
                        onViewTickets = { step = FeedbackStep.TICKETS },
                    )
                    FeedbackStep.CONSENT -> ConsentStep(
                        category = category ?: FeedbackCategory.BUG,
                        notificationControlAvailable = notificationControlAvailable,
                        onClose = onDismiss,
                        onBack = { step = FeedbackStep.CATEGORY },
                        onAgree = {
                            rememberCategory(category)
                            onStartRecording()
                        },
                        onSkip = {
                            rememberCategory(category)
                            step = FeedbackStep.REVIEW
                        },
                    )
                    FeedbackStep.TICKETS -> TicketsStep(
                        tickets = tickets,
                        onClose = onDismiss,
                        onBack = { step = FeedbackStep.CATEGORY },
                        onOpenTicket = { openIssue(context, it.issueUrl) },
                    )
                    FeedbackStep.REVIEW, FeedbackStep.SENDING -> Unit // handled above
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
private fun CategoryStep(
    onClose: () -> Unit,
    onPick: (FeedbackCategory) -> Unit,
    hasTickets: Boolean,
    onViewTickets: () -> Unit,
) {
    FeedbackStepScaffold(
        onClose = onClose,
        actions = {
            if (hasTickets) {
                TextButton(onClick = onViewTickets, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.feedback_tickets_entry))
                }
            }
        },
    ) {
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
    notificationControlAvailable: Boolean,
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
        if (!notificationControlAvailable) {
            // The foreground-service notification is silenced — tell the user
            // the in-app Stop pill is the way to finish (#1930 review).
            Spacer(Modifier.height(12.dp))
            NotificationWarning()
        }
    }
}

@Composable
private fun ReviewStep(
    recording: FeedbackRecording?,
    note: String,
    onNoteChange: (String) -> Unit,
    onClose: () -> Unit,
    onRerecord: () -> Unit,
    onSend: () -> Unit,
    emulator: Boolean = false,
) {
    // On an emulator the audio track is silent — Whisper will return an
    // empty transcript, so the GitHub issue will have no actionable content
    // unless the user types a note. Warn if no text yet (#2123).
    val showEmulatorHint = emulator && note.isBlank()
    FeedbackStepScaffold(
        onClose = onClose,
        actions = {
            PrimaryButton(
                text = stringResource(R.string.feedback_review_send),
                onClick = onSend,
                enabled = recording != null || note.isNotBlank(),
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
            // The Send button's enabled rule is `recording != null || note.isNotBlank()` —
            // when there is no recording the note becomes the only payload, so the copy here
            // must say so plainly. The previous "your note will be sent on its own" implied
            // the note was optional, which contradicted the disabled Send button (#2230).
            StepBody(stringResource(R.string.feedback_review_no_recording_note_required))
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = note,
            onValueChange = onNoteChange,
            // When there is no recording, the note is the only thing the team will see —
            // drop "optional" from the label and ask for the description directly (#2230).
            label = {
                Text(
                    stringResource(
                        if (recording == null) {
                            R.string.feedback_review_note_label_required
                        } else {
                            R.string.feedback_review_note_label
                        },
                    ),
                )
            },
            placeholder = { Text(stringResource(R.string.feedback_review_note_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            // On an emulator the mic is silent — show a supporting text hint so
            // the user knows a typed note is needed for the issue to be useful.
            supportingText = if (showEmulatorHint) {
                { Text(stringResource(R.string.feedback_review_emulator_hint)) }
            } else {
                null
            },
        )
    }
}

@Composable
private fun SendingStep(
    state: UploadUiState,
    onClose: () -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        is UploadUiState.Sending -> FeedbackStepScaffold(
            // A close affordance is always present while sending — the upload
            // runs in a coroutine that is cancelled when the dialog is
            // dismissed, so the user is never trapped on the spinner (#1930
            // review). It also doubles as the recovery exit if a process death
            // dropped the upload.
            onClose = onClose,
            actions = {
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.feedback_sending_cancel))
                }
            },
        ) {
            Spacer(Modifier.height(64.dp))
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(48.dp),
            )
            Spacer(Modifier.height(24.dp))
            StepBody(stringResource(R.string.feedback_sending))
            val progress = state.progress
            if (progress != null) {
                Spacer(Modifier.height(20.dp))
                // Determinate bar while the payload (mostly the screen
                // recording) is uploading; the spinner above covers the
                // worker-side processing once the body is fully sent.
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(
                        R.string.feedback_sending_progress,
                        (progress * 100).toInt(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is UploadUiState.Sent -> FeedbackStepScaffold(
            onClose = onClose,
            actions = {
                PrimaryButton(
                    text = stringResource(R.string.feedback_sent_done),
                    onClick = onClose,
                )
            },
        ) {
            Spacer(Modifier.height(16.dp))
            FeedbackHeroIcon(
                Icons.Outlined.CheckCircle,
                Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(24.dp))
            StepTitle(stringResource(R.string.feedback_sent_title))
            Spacer(Modifier.height(12.dp))
            StepBody(
                if (state.issueNumber != null) {
                    stringResource(R.string.feedback_sent_issue, state.issueNumber)
                } else {
                    stringResource(R.string.feedback_sent_generic)
                },
            )
        }

        is UploadUiState.Failed -> FeedbackStepScaffold(
            onClose = onClose,
            actions = {
                PrimaryButton(
                    text = stringResource(R.string.feedback_send_retry),
                    onClick = onRetry,
                )
            },
        ) {
            Spacer(Modifier.height(16.dp))
            FeedbackHeroIcon(
                Icons.Outlined.ErrorOutline,
                Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(24.dp))
            StepTitle(stringResource(R.string.feedback_send_failed_title))
            Spacer(Modifier.height(12.dp))
            // Tailored copy per failure kind — a 413 user must not be told to
            // "check your connection" forever (#1930 review).
            StepBody(
                stringResource(
                    when (state.errorKind) {
                        FeedbackUploader.ErrorKind.TOO_LARGE ->
                            R.string.feedback_send_failed_too_large
                        FeedbackUploader.ErrorKind.RATE_LIMITED ->
                            R.string.feedback_send_failed_rate_limited
                        FeedbackUploader.ErrorKind.SERVER ->
                            R.string.feedback_send_failed_server
                        else -> R.string.feedback_send_failed_body
                    },
                ),
            )
        }
    }
}

@Composable
private fun FailedStep(
    micPermanentlyDenied: Boolean,
    projectionRevoked: Boolean,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onSkipRecording: () -> Unit,
) {
    // When the microphone permission is "Don't ask again"-denied, a plain
    // "Retry" just loops a no-op (the system shows no dialog). Route the user to
    // app settings, or let them continue with a text-only note (#1930 review).
    if (micPermanentlyDenied) {
        FeedbackStepScaffold(
            onClose = onClose,
            actions = {
                PrimaryButton(
                    text = stringResource(R.string.feedback_record_open_settings),
                    onClick = onOpenSettings,
                )
                TextButton(
                    onClick = onSkipRecording,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.feedback_record_skip))
                }
            },
        ) {
            Spacer(Modifier.height(16.dp))
            FeedbackHeroIcon(
                Icons.Outlined.Mic,
                Modifier.align(Alignment.CenterHorizontally),
                error = true,
            )
            Spacer(Modifier.height(24.dp))
            StepTitle(stringResource(R.string.feedback_record_denied_title))
            Spacer(Modifier.height(12.dp))
            StepBody(stringResource(R.string.feedback_record_denied_body))
        }
        return
    }
    FeedbackStepScaffold(
        onClose = onClose,
        actions = {
            PrimaryButton(
                text = stringResource(R.string.feedback_record_retry),
                onClick = onRetry,
            )
            TextButton(onClick = onSkipRecording, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.feedback_record_skip))
            }
        },
    ) {
        Spacer(Modifier.height(16.dp))
        FeedbackHeroIcon(
            Icons.Outlined.ErrorOutline,
            Modifier.align(Alignment.CenterHorizontally),
            error = true,
        )
        Spacer(Modifier.height(24.dp))
        // A revoked projection (the system, or the user dismissing the cast
        // chip) is not a genuine failure — say so plainly instead of the
        // generic "recording didn't work" copy (#2030).
        StepTitle(
            stringResource(
                if (projectionRevoked) {
                    R.string.feedback_record_revoked_title
                } else {
                    R.string.feedback_record_failed_title
                },
            ),
        )
        Spacer(Modifier.height(12.dp))
        StepBody(
            stringResource(
                if (projectionRevoked) {
                    R.string.feedback_record_revoked_body
                } else {
                    R.string.feedback_record_failed_body
                },
            ),
        )
    }
}

@Composable
private fun TicketsStep(
    tickets: List<SubmittedFeedback>,
    onClose: () -> Unit,
    onBack: () -> Unit,
    onOpenTicket: (SubmittedFeedback) -> Unit,
) {
    // Bumped by the manual refresh action — clears the 5-minute status cache and
    // re-keys every row's GitHub fetch so a user can re-check a ticket
    // immediately rather than waiting out the TTL (#2030).
    var refreshNonce by rememberSaveable { mutableStateOf(0) }
    FeedbackStepScaffold(
        onClose = onClose,
        actions = {
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.feedback_tickets_back))
            }
        },
        scrollableContent = false,
    ) {
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                StepTitle(stringResource(R.string.feedback_tickets_title))
            }
            if (tickets.isNotEmpty()) {
                IconButton(
                    onClick = {
                        FeedbackTicketStatus.invalidateCache()
                        refreshNonce++
                    },
                ) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = stringResource(R.string.feedback_tickets_refresh),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        StepBody(stringResource(R.string.feedback_tickets_body))
        Spacer(Modifier.height(20.dp))
        if (tickets.isEmpty()) {
            StepBody(stringResource(R.string.feedback_tickets_empty))
        } else {
            // A LazyColumn so each row's GitHub status fetch fires only when the
            // row scrolls into view — not all at once on open.
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                items(tickets, key = { it.issueNumber }) { ticket ->
                    TicketRow(
                        ticket = ticket,
                        refreshNonce = refreshNonce,
                        onClick = { onOpenTicket(ticket) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TicketRow(ticket: SubmittedFeedback, refreshNonce: Int, onClick: () -> Unit) {
    // The GitHub issue is the source of truth — the live state is read from it,
    // never stored. null = still loading. Re-keyed on [refreshNonce] so the
    // manual "Refresh status" action re-fetches every row (#2030).
    val state by produceState<TicketState?>(
        initialValue = null,
        ticket.issueNumber,
        refreshNonce,
    ) {
        value = FeedbackTicketStatus.fetch(ticket.issueNumber)
    }
    val isBug = ticket.category == FeedbackCategory.BUG
    // A category accent, not an alert — these are the user's own filed tickets,
    // so `error` (reserved for alarms) would wrongly read as "something wrong".
    val tint = if (isBug) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.feedback_tickets_open_a11y),
                onClick = onClick,
            ),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(tint.copy(alpha = 0.18f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isBug) Icons.Outlined.BugReport else Icons.Outlined.Lightbulb,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    ticketDisplayTitle(ticket),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(
                        R.string.feedback_tickets_row_meta,
                        ticket.issueNumber,
                        relativeTime(ticket.createdAt),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TicketStatusBadge(state)
        }
    }
}

@Composable
private fun TicketStatusBadge(state: TicketState?) {
    when (state) {
        null -> {
            val loadingDesc = stringResource(R.string.feedback_tickets_status_loading)
            CircularProgressIndicator(
                modifier = Modifier
                    .size(16.dp)
                    .semantics { contentDescription = loadingDesc },
                strokeWidth = 2.dp,
            )
        }
        TicketState.OPEN -> StatusPill(
            text = stringResource(R.string.feedback_tickets_open),
            container = MaterialTheme.colorScheme.tertiaryContainer,
            content = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        TicketState.CLOSED -> StatusPill(
            text = stringResource(R.string.feedback_tickets_closed),
            container = MaterialTheme.colorScheme.surfaceContainerHighest,
            content = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Status unavailable (offline / rate-limited) — show nothing rather
        // than a misleading badge; the row still opens the real issue.
        TicketState.UNKNOWN -> Unit
    }
}

@Composable
private fun StatusPill(text: String, container: Color, content: Color) {
    Surface(shape = CircleShape, color = container) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = content,
        )
    }
}

@Composable
private fun ticketDisplayTitle(ticket: SubmittedFeedback): String =
    ticket.title.ifBlank {
        stringResource(
            if (ticket.category == FeedbackCategory.BUG) {
                R.string.feedback_tickets_untitled_bug
            } else {
                R.string.feedback_tickets_untitled_idea
            },
        )
    }

private fun relativeTime(createdAt: Long): String =
    DateUtils.getRelativeTimeSpanString(
        createdAt,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()

/** Open the real GitHub issue — the GitHub app handles it if installed, else a
 *  browser. A device with neither shows a toast rather than crashing. */
private fun openIssue(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        Toast.makeText(context, R.string.feedback_tickets_no_app, Toast.LENGTH_LONG).show()
    }
}

// ── Shared pieces ────────────────────────────────────────────────────────────

/**
 * Close row, content area, and a pinned action area at the bottom.
 *
 * @param scrollableContent when true (default) the content area is wrapped in a
 *   `verticalScroll`; pass false for a step that owns its own scrolling (e.g. a
 *   `LazyColumn`), since nesting two same-axis scroll containers is illegal.
 */
@Composable
private fun FeedbackStepScaffold(
    onClose: (() -> Unit)?,
    actions: @Composable ColumnScope.() -> Unit = {},
    scrollableContent: Boolean = true,
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
                .height(56.dp)
                .padding(4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            if (onClose != null) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.feedback_cd_close),
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .then(
                    if (scrollableContent) {
                        Modifier.verticalScroll(rememberScrollState())
                    } else {
                        Modifier
                    },
                )
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
private fun ColumnScope.PrimaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
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
private fun FeedbackHeroIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    error: Boolean = false,
) {
    val container =
        if (error) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.secondaryContainer
    val content =
        if (error) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onSecondaryContainer
    Box(
        modifier = modifier
            .size(72.dp)
            .background(container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = content,
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

/** One-time hint shown on the consent step when the foreground-service
 *  notification will be silent — the in-app Stop pill is then the stop path. */
@Composable
private fun NotificationWarning() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                stringResource(R.string.feedback_notif_warning),
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
                if (recording.capped) {
                    // The clip hit the size/duration cap and was auto-stopped —
                    // tell the user so the cut-off is not a surprise (#1930).
                    Text(
                        stringResource(R.string.feedback_recording_capped),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

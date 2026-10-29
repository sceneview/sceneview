package io.github.sceneview.demo.ai

import androidx.annotation.StringRes
import io.github.sceneview.demo.R

/**
 * Why Gemini Nano cannot answer on this phone right now, in the user's terms (#2648).
 *
 * Before this existed every "no" collapsed into one card, "Gemini Nano isn't available on this
 * device", with no reason and no way to ask again. On a Pixel 9 that is the wrong answer more
 * often than not: ML Kit's `FeatureStatus.UNAVAILABLE` also means "AICore has not fetched its
 * latest configuration yet", and AICore's setup errors (`BINDING_FAILURE`,
 * `FEATURE_NOT_FOUND`) clear by themselves after minutes or hours. A permanent, button-less
 * verdict turned those into "never managed to make it work".
 *
 * Every reason therefore names what happened ([messageRes]) and the card always offers
 * "Check again" — re-probing is free, and it is the one action that recovers every transient
 * cause. [offersAicoreUpdate] adds the Play listing where an AICore update can help.
 */
enum class AskUnavailableReason(
    @StringRes val messageRes: Int,
    val offersAicoreUpdate: Boolean,
) {
    /** No AICore on the phone at all — nothing on it can run Gemini Nano. */
    NoAicore(R.string.demo_point_and_ask_unavailable_reason_no_aicore, offersAicoreUpdate = false),

    /**
     * `FeatureStatus.UNAVAILABLE` with AICore installed: either the phone is not supported, or
     * AICore has not fetched its configuration yet — ML Kit does not say which.
     */
    NotSupported(R.string.demo_point_and_ask_unavailable_reason_not_supported, offersAicoreUpdate = true),

    /** AICore is installed but still setting up (`BINDING_FAILURE` 601, `FEATURE_NOT_FOUND` 606). */
    AicoreNotReady(R.string.demo_point_and_ask_unavailable_reason_not_ready, offersAicoreUpdate = true),

    /** `NEEDS_SYSTEM_UPDATE` (604) — AICore is older than the ML Kit client. */
    NeedsSystemUpdate(R.string.demo_point_and_ask_unavailable_reason_update, offersAicoreUpdate = true),

    /** The status check threw something we could not classify. */
    StatusCheckFailed(R.string.demo_point_and_ask_unavailable_reason_check_failed, offersAicoreUpdate = false),

    /** The status check never answered — AICore hung instead of saying no. */
    StatusCheckTimedOut(R.string.demo_point_and_ask_unavailable_reason_timeout, offersAicoreUpdate = false),
}

/**
 * Maps a GenAI error code (from a status check or a download) onto an [AskUnavailableReason].
 * `null` (a throwable that carried no `GenAiException`) and unknown codes become
 * [AskUnavailableReason.StatusCheckFailed] — never a guess.
 *
 * 601 and 606 are AICore *service* codes documented on the ML Kit troubleshooting page, not
 * members of `GenAiException.ErrorCode`, so they are mirrored here by value.
 */
fun unavailableReasonOfErrorCode(code: Int?): AskUnavailableReason = when (code) {
    GENAI_BINDING_FAILURE, GENAI_FEATURE_NOT_FOUND -> AskUnavailableReason.AicoreNotReady
    GENAI_NEEDS_SYSTEM_UPDATE -> AskUnavailableReason.NeedsSystemUpdate
    GENAI_NOT_AVAILABLE, GENAI_NOT_SUPPORTED, GENAI_AICORE_INCOMPATIBLE -> AskUnavailableReason.NotSupported
    else -> AskUnavailableReason.StatusCheckFailed
}

/**
 * Maps `FeatureStatus` (an `@IntDef`, mirrored by value — see [AskFailure]'s note on why
 * Kotlin cannot address annotation constants) onto the engine status the demo speaks.
 * An unknown value is treated as unavailable-with-reason rather than as "ready".
 */
fun askEngineStatusOfFeatureStatus(featureStatus: Int): AskEngineStatus = when (featureStatus) {
    FEATURE_STATUS_AVAILABLE -> AskEngineStatus.Ready
    FEATURE_STATUS_DOWNLOADABLE -> AskEngineStatus.Downloadable
    FEATURE_STATUS_DOWNLOADING -> AskEngineStatus.Downloading()
    else -> AskEngineStatus.Unavailable(AskUnavailableReason.NotSupported)
}

/**
 * Percent of the model downloaded, `null` while the total is unknown (before ML Kit's
 * `DownloadStarted`, or when it reports 0 bytes to fetch). Clamped to 0..100: AICore has been
 * seen to report a few bytes past its own announced total.
 */
fun downloadPercent(bytesDownloaded: Long?, bytesToDownload: Long?): Int? {
    if (bytesDownloaded == null || bytesToDownload == null || bytesToDownload <= 0L) return null
    return ((bytesDownloaded.coerceAtLeast(0L) * PERCENT) / bytesToDownload).toInt().coerceIn(0, PERCENT.toInt())
}

/**
 * One stable logcat line per state the user can see, under [ASK_STATE_LOG_TAG], so a phone
 * proof reads `adb logcat -s PointAndAsk` and gets the same sequence the screen showed
 * (#2648). Kept free of answer text: an answer is the user's content, not a diagnostic.
 */
fun AskStep.logLine(): String = when (this) {
    AskStep.CheckingAvailability -> "state=checking"
    AskStep.ModelDownloadable -> "state=downloadable"
    is AskStep.ModelDownloading ->
        "state=downloading bytes=${bytesDownloaded ?: "?"} total=${bytesToDownload ?: "?"}" +
            " percent=${downloadPercent(bytesDownloaded, bytesToDownload) ?: "?"}"
    is AskStep.ModelDownloadFailed -> "state=download-failed failure=$failure"
    is AskStep.ModelUnsupported -> "state=unavailable reason=$reason"
    AskStep.Ready -> "state=ready"
    AskStep.CapturingFrame -> "state=capturing"
    AskStep.Thinking -> "state=thinking"
    is AskStep.Answered -> "state=answered streaming=$streaming chars=${text.length}"
    is AskStep.Failed -> "state=failed failure=$failure persistent=$persistent"
}

/**
 * What has to change for [logLine] to be worth another logcat line: the state type, the whole
 * download percent, the streaming flag — never a single byte or token. AICore reports a 1 GB
 * download in thousands of ticks and an answer streams token by token; keying the log on the
 * raw step wrote one line per tick (review of #4602).
 */
fun AskStep.logKey(): Any = when (this) {
    is AskStep.ModelDownloading -> "downloading:${downloadPercent(bytesDownloaded, bytesToDownload)}"
    is AskStep.Answered -> "answered:$streaming"
    else -> this
}

/** Logcat tag for the user-visible state sequence — `adb logcat -s PointAndAsk`. */
const val ASK_STATE_LOG_TAG = "PointAndAsk"

private const val PERCENT = 100L

private const val FEATURE_STATUS_DOWNLOADABLE = 1
private const val FEATURE_STATUS_DOWNLOADING = 2
private const val FEATURE_STATUS_AVAILABLE = 3

private const val GENAI_NOT_AVAILABLE = 8
private const val GENAI_NOT_SUPPORTED = 16
private const val GENAI_AICORE_INCOMPATIBLE = -101
private const val GENAI_NEEDS_SYSTEM_UPDATE = 604
private const val GENAI_BINDING_FAILURE = 601
private const val GENAI_FEATURE_NOT_FOUND = 606

/**
 * Budget for `checkStatus()`. AICore binds over IPC; a status check that never answers used
 * to leave the demo on "Checking Gemini Nano availability…" forever (#2648).
 */
const val ASK_STATUS_TIMEOUT_MS = 15_000L

/**
 * Budget for the first streamed token of an answer. Generous on purpose — the first inference
 * after boot loads Gemini Nano into memory — but finite, so a hung inference becomes a
 * retryable [AskFailure.TimedOut] instead of an endless "thinking" (#2648).
 */
const val ASK_FIRST_TOKEN_TIMEOUT_MS = 60_000L

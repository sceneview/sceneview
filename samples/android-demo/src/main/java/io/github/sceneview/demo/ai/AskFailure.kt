package io.github.sceneview.demo.ai

import androidx.annotation.StringRes
import com.google.mlkit.genai.common.GenAiException
import io.github.sceneview.demo.R

/**
 * Why one Point & Ask round failed, in the terms the *user* needs — not the ones ML Kit
 * uses (#3343).
 *
 * Before this existed, every failure mode collapsed into a single string ("Gemini Nano
 * couldn't answer — tap to try again"): a device with no AICore support, a model evicted
 * by a system update, a request over the token budget, a compositor that handed back an
 * empty frame and a plain inference error were indistinguishable, on screen *and* in
 * logcat. A user whose device can never answer was invited to tap again forever, which is
 * exactly the report in #3343 — "j'ai que des Gemini can't answer".
 *
 * Each value therefore carries:
 *  - [messageRes], one sentence saying what happened;
 *  - [isTerminal], whether the **platform** reported that this device cannot run the model
 *    at all. Only a terminal failure may retire the demo (`AskAvailability.Unsupported`);
 *    a pile of non-terminal ones may not, however tall (#3407);
 *  - [recovery], the one next step the card offers — a real button, not a sentence.
 */
enum class AskFailure(
    @StringRes val messageRes: Int,
    val isTerminal: Boolean,
    val recovery: AskRecovery,
) {
    /** The frame never arrived (read-back failure or timeout). */
    CaptureFailed(
        R.string.demo_point_and_ask_error_capture,
        isTerminal = false,
        recovery = AskRecovery.Retry,
    ),

    /**
     * The read-back came back with the AR viewport punched out — a fully transparent region
     * where the camera + virtual objects should be (#2654/#3276). Retrying is worth one shot
     * (compositor state changes frame to frame), and the message says what is missing rather
     * than blaming the model.
     */
    CaptureMissingArLayer(
        R.string.demo_point_and_ask_error_capture_layer,
        isTerminal = false,
        recovery = AskRecovery.Retry,
    ),

    /**
     * The frame was read back intact but is essentially one flat colour — a black viewport,
     * a covered lens, or a lost AR layer in its opaque form. Sending it produced exactly the
     * "the model sees nothing" report in #3407, so it is now caught before it leaves.
     */
    CaptureBlank(
        R.string.demo_point_and_ask_error_capture_blank,
        isTerminal = false,
        recovery = AskRecovery.AimAndRetry,
    ),

    /**
     * The model ran and returned nothing. Not an exception — a completed stream with no
     * text, which the pre-#3343 code reported with the same wording as a hard failure.
     */
    EmptyAnswer(
        R.string.demo_point_and_ask_error_empty,
        isTerminal = false,
        recovery = AskRecovery.Rephrase,
    ),

    /** `NOT_AVAILABLE` / `NOT_SUPPORTED` / `AICORE_INCOMPATIBLE`. */
    ModelUnavailable(
        R.string.demo_point_and_ask_error_unavailable,
        isTerminal = true,
        recovery = AskRecovery.OpenAicoreSettings,
    ),

    /** `NEEDS_SYSTEM_UPDATE` — AICore is behind the ML Kit client. */
    NeedsSystemUpdate(
        R.string.demo_point_and_ask_error_system_update,
        isTerminal = true,
        recovery = AskRecovery.OpenAicoreSettings,
    ),

    /** `NOT_ENOUGH_DISK_SPACE`. */
    NotEnoughDiskSpace(
        R.string.demo_point_and_ask_error_disk_space,
        isTerminal = false,
        recovery = AskRecovery.FreeStorage,
    ),

    /** `BUSY` / battery quota / background-use block — transient, retry genuinely helps. */
    Busy(
        R.string.demo_point_and_ask_error_busy,
        isTerminal = false,
        recovery = AskRecovery.Retry,
    ),

    /** `REQUEST_TOO_LARGE` / `REQUEST_TOO_SMALL` — the prompt itself is out of bounds. */
    RequestTooLarge(
        R.string.demo_point_and_ask_error_too_large,
        isTerminal = false,
        recovery = AskRecovery.Rephrase,
    ),

    /** `INVALID_INPUT_IMAGE` — the captured frame was rejected by the model. */
    InvalidImage(
        R.string.demo_point_and_ask_error_image,
        isTerminal = false,
        recovery = AskRecovery.AimAndRetry,
    ),

    /** Anything else, including a non-ML-Kit throwable. */
    Unknown(
        R.string.demo_point_and_ask_error,
        isTerminal = false,
        recovery = AskRecovery.Retry,
    ),
    ;

    companion object {
        /**
         * Maps a throwable raised by the ML Kit GenAI Prompt API onto an [AskFailure].
         *
         * Codes come from `GenAiException.ErrorCode` (genai-common 1.0.0-beta4). Unknown
         * codes deliberately fall through to [Unknown] rather than being guessed at: a
         * beta artifact adds codes between releases, and inventing a reassuring message
         * for a code we have never seen is worse than saying "something went wrong".
         *
         * The cause chain is walked because ML Kit wraps the AICore failure in whatever
         * the coroutine adapter threw on some paths.
         */
        fun of(error: Throwable?): AskFailure {
            var current = error
            var depth = 0
            while (current != null && depth < MAX_CAUSE_DEPTH) {
                if (current is GenAiException) return ofErrorCode(current.errorCode)
                current = current.cause.takeIf { it !== current }
                depth++
            }
            return Unknown
        }

        /** Visible for tests — the pure code → failure mapping, no throwable needed. */
        fun ofErrorCode(code: Int): AskFailure = when (code) {
            NOT_AVAILABLE, NOT_SUPPORTED, AICORE_INCOMPATIBLE -> ModelUnavailable
            NEEDS_SYSTEM_UPDATE -> NeedsSystemUpdate
            NOT_ENOUGH_DISK_SPACE -> NotEnoughDiskSpace
            BUSY, PER_APP_BATTERY_USE_QUOTA_EXCEEDED, BACKGROUND_USE_BLOCKED -> Busy
            REQUEST_TOO_LARGE, REQUEST_TOO_SMALL -> RequestTooLarge
            INVALID_INPUT_IMAGE -> InvalidImage
            else -> Unknown
        }

        /**
         * `GenAiException.ErrorCode` values, mirrored as plain constants.
         *
         * Copied rather than referenced because that type is a Java *annotation* type
         * (`@interface ErrorCode`), and Kotlin maps annotation types to annotation classes
         * whose Java static fields it cannot address — `GenAiException.ErrorCode.BUSY`
         * does not compile from Kotlin. Values are from genai-common 1.0.0-beta4; an
         * unrecognised code degrades to [Unknown], so a drifted value is a lost nuance,
         * never a wrong message.
         */
        private const val NOT_AVAILABLE = 8
        private const val BUSY = 9
        private const val REQUEST_TOO_LARGE = 12
        private const val NOT_SUPPORTED = 16
        private const val PER_APP_BATTERY_USE_QUOTA_EXCEEDED = 27
        private const val BACKGROUND_USE_BLOCKED = 30
        private const val NOT_ENOUGH_DISK_SPACE = 501
        private const val NEEDS_SYSTEM_UPDATE = 604
        private const val REQUEST_TOO_SMALL = -100
        private const val AICORE_INCOMPATIBLE = -101
        private const val INVALID_INPUT_IMAGE = -102

        /** Guards against a self-referential or pathologically deep cause chain. */
        private const val MAX_CAUSE_DEPTH = 8
    }
}

/**
 * The one concrete next step a failure card offers, as a button (#3407).
 *
 * The #3343 card ended at a sentence — and once a retry counter tripped, at a sentence that
 * told a working Pixel 9 it could not run the model. Every failure now names an action the
 * user can actually take, and [openable] says whether taking it leaves the app.
 */
enum class AskRecovery(@StringRes val labelRes: Int, val openable: Boolean = false) {
    /** Just try the round again — nothing about the device needs to change. */
    Retry(R.string.demo_point_and_ask_action_retry),

    /** Point at something with a bit of texture and light, then ask again. */
    AimAndRetry(R.string.demo_point_and_ask_action_aim),

    /** The question, not the frame, is the problem — put the cursor back in the field. */
    Rephrase(R.string.demo_point_and_ask_action_rephrase),

    /** Free space, then retry — opens the storage settings screen. */
    FreeStorage(R.string.demo_point_and_ask_action_storage, openable = true),

    /**
     * Opens the system app details for Android System Intelligence / AICore, where the user
     * can check for an update. The only action offered for the two terminal causes: there is
     * no cloud fallback by design (#2648), so the honest next step is a platform one.
     */
    OpenAicoreSettings(R.string.demo_point_and_ask_action_aicore, openable = true),
}

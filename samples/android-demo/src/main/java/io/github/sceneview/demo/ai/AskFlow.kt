package io.github.sceneview.demo.ai

/**
 * Availability of the on-device engine, as the flow tracks it. Mirrors [AskEngineStatus] but
 * is independent of ML Kit types so the whole state machine stays a plain-Kotlin, JVM-testable
 * unit (#3407).
 */
sealed interface AskAvailability {
    /** The availability probe has not answered yet. */
    data object Checking : AskAvailability

    /** Supported device, model not fetched — the user can start the download. */
    data object Downloadable : AskAvailability

    /** Download in flight. `null` bytes until the first progress tick. */
    data class Downloading(val bytesDownloaded: Long? = null) : AskAvailability

    /** Model present — the demo can be asked questions. */
    data object Ready : AskAvailability

    /**
     * The **platform** reported that this device cannot run the model. Only ever reached
     * from a real report — `FeatureStatus` saying so, a failed download, or a terminal ML
     * Kit error code — never from a retry counter (#3407).
     */
    data object Unsupported : AskAvailability
}

/** What the demo is doing about the current question, once the model is [AskAvailability.Ready]. */
sealed interface AskRound {
    /** Nothing in flight — tap to ask. */
    data object Idle : AskRound

    /** A tap was registered; the AR frame is being read back and validated. */
    data object CapturingFrame : AskRound

    /** A valid frame is with the model. */
    data object Thinking : AskRound

    /** Answer text so far; [streaming] while deltas keep arriving. */
    data class Answered(val text: String, val streaming: Boolean) : AskRound

    /**
     * The round failed before any text arrived. [consecutive] is how many rounds in a row
     * have now failed — used only to change the *wording* ("this keeps failing"), never to
     * declare the device unsupported.
     */
    data class Failed(val failure: AskFailure, val consecutive: Int) : AskRound
}

/**
 * The single state the Point & Ask UI renders. Exactly one card is on screen at a time, so
 * exactly one value describes it.
 */
sealed interface AskStep {
    data object CheckingAvailability : AskStep
    data object ModelDownloadable : AskStep
    data class ModelDownloading(val bytesDownloaded: Long?) : AskStep
    data object ModelUnsupported : AskStep
    data object Ready : AskStep
    data object CapturingFrame : AskStep
    data object Thinking : AskStep
    data class Answered(val text: String, val streaming: Boolean) : AskStep

    /**
     * [persistent] is `true` once the same round has failed
     * [ASK_FAILURE_PERSISTENT_THRESHOLD] times in a row. It changes the headline from "that
     * didn't work" to "this keeps failing" — and nothing else. The card still names
     * [failure]'s actual cause and still offers [AskFailure.recovery]. A device is declared
     * unable to run the model only through [AskStep.ModelUnsupported], which only the
     * platform can trigger (#3407).
     */
    data class Failed(val failure: AskFailure, val persistent: Boolean) : AskStep
}

/**
 * Consecutive failures after which the failure card changes its headline to "this keeps
 * failing here". It does **not** retire the retry, and it does **not** claim the device is
 * unsupported: #3343 wired a retry counter straight to "Point & Ask can't answer on this
 * device", so three ordinary capture failures on a perfectly capable Pixel 9 ended the demo
 * with a dead-end lie (#3407).
 */
const val ASK_FAILURE_PERSISTENT_THRESHOLD = 3

/**
 * The Point & Ask state machine, as a plain object with no Android, Compose, ARCore or ML Kit
 * dependency — every transition below is exercised by `AskFlowTest` on the JVM.
 *
 * Two orthogonal axes, deliberately kept apart:
 *  - [availability] — can this device answer at all? Owned by the engine probe and the
 *    download flow, and by terminal ML Kit error codes. This is the ONLY axis that can say
 *    "not on this phone".
 *  - [round] — what is happening to the current question. Owned by the tap, the frame
 *    capture, and the inference stream. However many rounds fail, this axis never promotes
 *    itself to "unsupported".
 *
 * [step] projects both onto the one card the user sees.
 */
class AskFlow(
    availability: AskAvailability = AskAvailability.Checking,
    round: AskRound = AskRound.Idle,
) {
    var availability: AskAvailability = availability
        private set

    var round: AskRound = round
        private set

    /** How many rounds in a row have failed with no text at all. Reset by any answer. */
    var consecutiveFailures: Int = 0
        private set

    /** The single state the UI renders. */
    val step: AskStep
        get() = when (val a = availability) {
            AskAvailability.Checking -> AskStep.CheckingAvailability
            AskAvailability.Downloadable -> AskStep.ModelDownloadable
            is AskAvailability.Downloading -> AskStep.ModelDownloading(a.bytesDownloaded)
            AskAvailability.Unsupported -> AskStep.ModelUnsupported
            AskAvailability.Ready -> when (val r = round) {
                AskRound.Idle -> AskStep.Ready
                AskRound.CapturingFrame -> AskStep.CapturingFrame
                AskRound.Thinking -> AskStep.Thinking
                is AskRound.Answered -> AskStep.Answered(r.text, r.streaming)
                is AskRound.Failed -> AskStep.Failed(
                    failure = r.failure,
                    persistent = r.consecutive >= ASK_FAILURE_PERSISTENT_THRESHOLD,
                )
            }
        }

    /**
     * `true` while a round is in flight. Taps are ignored, and the round cannot be replaced
     * out from under itself.
     */
    val isBusy: Boolean
        get() = round == AskRound.CapturingFrame ||
            round == AskRound.Thinking ||
            (round as? AskRound.Answered)?.streaming == true

    /** `true` when a tap would start a round right now. */
    val canAsk: Boolean
        get() = availability == AskAvailability.Ready && !isBusy

    /**
     * The engine probe (or the download flow) reported [status]. A status change while a
     * round is in flight abandons the round: the answer it was going to produce is no longer
     * meaningful once the model has gone away, and leaving it "thinking" forever is exactly
     * the wedge #3188 fixed.
     */
    fun onAvailability(status: AskAvailability) {
        if (status == availability) return
        availability = status
        if (status != AskAvailability.Ready) {
            round = AskRound.Idle
        }
    }

    /**
     * A tap landed. Returns `true` when it starts a round (and the caller should capture a
     * frame), `false` when it was ignored — no model, or a round already running.
     */
    fun onTap(): Boolean {
        if (!canAsk) return false
        round = AskRound.CapturingFrame
        return true
    }

    /** A frame passed [inspectAskFrame] and is on its way to the model. */
    fun onFrameAccepted() {
        if (round == AskRound.CapturingFrame) round = AskRound.Thinking
    }

    /** One streamed delta; [text] is the answer accumulated so far. */
    fun onDelta(text: String) {
        if (round !is AskRound.CapturingFrame && round !is AskRound.Thinking &&
            round !is AskRound.Answered
        ) {
            return
        }
        consecutiveFailures = 0
        round = AskRound.Answered(text, streaming = true)
    }

    /**
     * The stream completed normally. A stream that produced no text at all is a failure the
     * user can act on (rephrase), not a silent success — it is one of the two shapes #3407
     * was reported as.
     */
    fun onStreamCompleted() {
        val answered = round as? AskRound.Answered
        if (answered == null || answered.text.isBlank()) {
            onFailure(AskFailure.EmptyAnswer)
            return
        }
        round = AskRound.Answered(answered.text, streaming = false)
    }

    /**
     * The round failed. Text already streamed is kept and marked complete — a partial answer
     * beats an error card. Otherwise the failure is surfaced, and a **terminal** failure
     * (the platform saying this device cannot run the model) also moves [availability] to
     * [AskAvailability.Unsupported], which is the only honest route to "not on this phone".
     */
    fun onFailure(failure: AskFailure) {
        val partial = (round as? AskRound.Answered)?.text?.takeIf { it.isNotBlank() }
        if (partial != null) {
            consecutiveFailures = 0
            round = AskRound.Answered(partial, streaming = false)
            return
        }
        consecutiveFailures++
        round = AskRound.Failed(failure, consecutiveFailures)
        if (failure.isTerminal) availability = AskAvailability.Unsupported
    }

    /** A finished answer timed out on screen. */
    fun onAnswerDismissed() {
        val answered = round as? AskRound.Answered ?: return
        if (!answered.streaming) round = AskRound.Idle
    }

    /**
     * The user tapped the failure card's action. The counter goes back to zero: they made a
     * deliberate gesture, so the next failure is a first failure again and the wording starts
     * over instead of staying escalated forever.
     */
    fun onRetry() {
        consecutiveFailures = 0
        round = AskRound.Idle
    }

    /** Demo Reset — clears the round and the failure history, keeps the availability probe. */
    fun reset() {
        consecutiveFailures = 0
        round = AskRound.Idle
    }
}

/**
 * QA-only step override (`--es qa_state <id>`, the shared QA state seam — see
 * `DemoSettings.qaDemoState`), so every card can be screenshot in light and dark on an
 * emulator that has neither ARCore nor AICore (#2754). Returns `null` for an absent or
 * unrecognised id, which leaves the demo running its real state machine — including the
 * Cloud Anchor ids that travel through the same extra.
 *
 * Pure lookup — unit-tested rather than trusted, because a typo here would silently produce
 * a QA sweep of the wrong card.
 */
fun askStepForQaOverride(id: String?): AskStep? = when (id?.trim()?.lowercase()) {
    null, "" -> null
    "checking" -> AskStep.CheckingAvailability
    "downloadable" -> AskStep.ModelDownloadable
    "downloading" -> AskStep.ModelDownloading(bytesDownloaded = 412L * 1024 * 1024)
    "unsupported" -> AskStep.ModelUnsupported
    "ready" -> AskStep.Ready
    "capturing" -> AskStep.CapturingFrame
    "thinking" -> AskStep.Thinking
    "answered" -> AskStep.Answered(QA_SAMPLE_ANSWER, streaming = false)
    "streaming" -> AskStep.Answered(QA_SAMPLE_ANSWER.take(34), streaming = true)
    "failed" -> AskStep.Failed(AskFailure.CaptureBlank, persistent = false)
    "failed-persistent" -> AskStep.Failed(AskFailure.CaptureMissingArLayer, persistent = true)
    else -> null
}

/** Every id [askStepForQaOverride] accepts, in the order a QA sweep should walk them. */
val ASK_QA_STATE_IDS = listOf(
    "checking",
    "downloadable",
    "downloading",
    "unsupported",
    "ready",
    "capturing",
    "thinking",
    "streaming",
    "answered",
    "failed",
    "failed-persistent",
)

/** Answer text used by the QA overrides. Not localized — the harness asserts on it. */
private const val QA_SAMPLE_ANSWER =
    "A small wooden side table with a potted plant on it, lit from the window on the left."

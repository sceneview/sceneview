package io.github.sceneview.demo.ai

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import io.github.sceneview.demo.DemoSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull

/** Availability of the on-device answer engine, as the demo UI sees it. */
sealed interface AskEngineStatus {
    /** Model present and ready to answer. */
    data object Ready : AskEngineStatus

    /** Supported device, model not fetched yet — offer a download CTA. */
    data object Downloadable : AskEngineStatus

    /** Model download in flight. [totalBytesDownloaded] is `null` until the first progress tick. */
    data class Downloading(val totalBytesDownloaded: Long? = null) : AskEngineStatus

    /** Unsupported device — no AICore (every emulator, and phones below Pixel 8 class). */
    data object Unavailable : AskEngineStatus
}

/**
 * Seam between the Point & Ask demo UI and ML Kit's GenAI Prompt API (`genai-prompt`, beta).
 *
 * Two reasons this indirection exists rather than calling ML Kit inline:
 *  - the Prompt API is a beta artifact — churn stays contained to [GeminiNanoAskEngine];
 *  - AICore/Gemini Nano is structurally unavailable on emulators, so device-QA runs swap in
 *    [CannedAskEngine] (via [rememberAskEngine] + `DemoSettings.qaMode`) and the full
 *    tap → capture → answer UI flow stays testable without AICore hardware.
 */
interface AskEngine {
    suspend fun status(): AskEngineStatus

    /** Triggers the model download, re-emitting progress; ends on [AskEngineStatus.Ready] or [AskEngineStatus.Unavailable]. */
    fun download(): Flow<AskEngineStatus>

    /**
     * One image + question round-trip, streamed: emits text **deltas** as the model produces
     * them (P3 of #2648 — progressive display). The flow completes normally when the answer
     * is done and throws on inference failure. The caller owns [image] and may recycle it
     * once the flow terminates.
     */
    fun askStream(image: Bitmap, question: String): Flow<String>

    fun close()
}

/** Real engine — Gemini Nano on-device through ML Kit GenAI Prompt API / AICore. */
class GeminiNanoAskEngine : AskEngine {

    private var clientCreated = false
    private val model: GenerativeModel by lazy {
        clientCreated = true
        Generation.getClient()
    }

    override suspend fun status(): AskEngineStatus = try {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> AskEngineStatus.Ready
            FeatureStatus.DOWNLOADABLE -> AskEngineStatus.Downloadable
            FeatureStatus.DOWNLOADING -> AskEngineStatus.Downloading()
            else -> AskEngineStatus.Unavailable
        }
    } catch (e: Throwable) {
        // AICore missing/broken surfaces as runtime exceptions on some devices, and a
        // minified build missing the ML Kit classes as a NoClassDefFoundError (an Error, not
        // an Exception). Same answer for the user either way: not available here. Leaving
        // an Error uncaught would keep the demo's status `null` forever — a blank bottom
        // edge instead of the "unavailable" banner (#3188).
        if (e is kotlinx.coroutines.CancellationException) throw e
        AskEngineStatus.Unavailable
    }

    override fun download(): Flow<AskEngineStatus> = flow {
        emit(AskEngineStatus.Downloading())
        try {
            model.download().collect { downloadStatus ->
                when (downloadStatus) {
                    is DownloadStatus.DownloadProgress ->
                        emit(AskEngineStatus.Downloading(downloadStatus.totalBytesDownloaded))
                    is DownloadStatus.DownloadCompleted -> emit(AskEngineStatus.Ready)
                    is DownloadStatus.DownloadFailed -> emit(AskEngineStatus.Unavailable)
                    else -> Unit // DownloadStarted — the initial Downloading emit covers it.
                }
            }
        } catch (_: Exception) {
            emit(AskEngineStatus.Unavailable)
        }
    }

    override fun askStream(image: Bitmap, question: String): Flow<String> =
        model.generateContentStream(
            generateContentRequest(ImagePart(image), TextPart(question)) {}
        ).mapNotNull { chunk ->
            // Each streamed GenerateContentResponse carries a text DELTA.
            chunk.candidates.firstOrNull()?.text?.takeIf { it.isNotEmpty() }
        }

    override fun close() {
        if (clientCreated) runCatching { model.close() }
    }
}

/**
 * Deterministic stand-in used when `DemoSettings.qaMode` is on.
 *
 * The answer text is intentionally NOT localized and starts with a stable marker so the QA
 * harness (Maestro / layout dumps) can assert it across locales. The small delay keeps the
 * "thinking" UI state observable.
 */
class CannedAskEngine : AskEngine {

    override suspend fun status(): AskEngineStatus = AskEngineStatus.Ready

    override fun download(): Flow<AskEngineStatus> = flowOf(AskEngineStatus.Ready)

    override fun askStream(image: Bitmap, question: String): Flow<String> = flow {
        delay(CANNED_LATENCY_MS)
        // Echo the question so QA can prove the free-form-question plumbing end-to-end,
        // then stream word by word so the progressive-display UI state is observable.
        val answer = "Canned QA answer to \"$question\" — ${image.width}×${image.height} frame. " +
            "Real Gemini Nano answers need an AICore device (Pixel 8+), not an emulator."
        answer.split(" ").forEachIndexed { i, word ->
            if (i > 0) delay(CANNED_WORD_DELAY_MS)
            emit(if (i == 0) word else " $word")
        }
    }

    override fun close() = Unit

    private companion object {
        const val CANNED_LATENCY_MS = 600L
        const val CANNED_WORD_DELAY_MS = 50L
    }
}

/**
 * Projects the ML Kit-flavoured [AskEngineStatus] onto the plain-Kotlin [AskAvailability] the
 * state machine speaks. The seam exists so `AskFlow` — and its JVM tests — never has to touch
 * an ML Kit type (#3407).
 *
 * [AskEngineStatus.Unavailable] is the one and only route to [AskAvailability.Unsupported]
 * that does not come from a terminal inference error: both are the **platform** reporting
 * that this device cannot run the model. Nothing else in the demo may reach that state.
 */
fun AskEngineStatus.toAvailability(): AskAvailability = when (this) {
    AskEngineStatus.Ready -> AskAvailability.Ready
    AskEngineStatus.Downloadable -> AskAvailability.Downloadable
    is AskEngineStatus.Downloading -> AskAvailability.Downloading(totalBytesDownloaded)
    AskEngineStatus.Unavailable -> AskAvailability.Unsupported
}

/**
 * Engine for the current run: [CannedAskEngine] under QA mode, [GeminiNanoAskEngine] otherwise.
 * Closed automatically when it leaves composition (or when QA mode is toggled mid-session).
 */
@Composable
fun rememberAskEngine(): AskEngine {
    val engine = remember(DemoSettings.qaMode) {
        if (DemoSettings.qaMode) CannedAskEngine() else GeminiNanoAskEngine()
    }
    DisposableEffect(engine) {
        onDispose { engine.close() }
    }
    return engine
}

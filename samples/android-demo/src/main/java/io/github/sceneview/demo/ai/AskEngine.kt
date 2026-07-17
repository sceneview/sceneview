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

    /** One image + question round-trip. The caller owns [image] and may recycle it afterwards. */
    suspend fun ask(image: Bitmap, question: String): Result<String>

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
    } catch (_: Exception) {
        // AICore missing/broken surfaces as runtime exceptions on some devices — same answer
        // for the user either way: not available here.
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

    override suspend fun ask(image: Bitmap, question: String): Result<String> = runCatching {
        val response = model.generateContent(
            generateContentRequest(ImagePart(image), TextPart(question)) {}
        )
        response.candidates.firstOrNull()?.text?.takeIf { it.isNotBlank() }
            ?: error("Gemini Nano returned an empty answer")
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

    override suspend fun ask(image: Bitmap, question: String): Result<String> {
        delay(CANNED_LATENCY_MS)
        return Result.success(
            "Canned QA answer — captured a ${image.width}×${image.height} camera frame. " +
                "Real Gemini Nano answers need an AICore device (Pixel 8+), not an emulator."
        )
    }

    override fun close() = Unit

    private companion object {
        const val CANNED_LATENCY_MS = 600L
    }
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

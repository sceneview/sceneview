package io.github.sceneview.demo.feedback

import io.github.sceneview.demo.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.Sink
import okio.buffer
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Uploads feedback submissions to the SceneView feedback worker. */
object FeedbackUploader {

    /**
     * Endpoint of the feedback worker — `<base>/v1/feedback`. The base URL is a
     * single configurable [BuildConfig] field (`FEEDBACK_WORKER_URL`), set in
     * `samples/android-demo/build.gradle`. The worker is deployed at
     * `sceneview-feedback.mcp-tools-lab.workers.dev`; the build can still point
     * the field at a staging URL via the env var / `local.properties`.
     */
    private val endpoint: String by lazy {
        BuildConfig.FEEDBACK_WORKER_URL.trimEnd('/') + "/v1/feedback"
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /** Why an upload failed — drives a tailored error message in the UI. */
    enum class ErrorKind {
        /** No failure. */
        NONE,

        /** Network unreachable / timeout — "check your connection". */
        NETWORK,

        /** 413 — the recording was too large for the worker's upload cap. */
        TOO_LARGE,

        /** 429 — too many submissions; retry later (see [Result.retryAfterSeconds]). */
        RATE_LIMITED,

        /** 5xx — a transient worker-side error. */
        SERVER,

        /** Any other non-success (4xx) or an unparseable success body. */
        OTHER,
    }

    /**
     * Outcome of an upload. [ok] is true when the worker accepted the feedback.
     * [issueNumber] / [issueUrl] are null when the worker stored the feedback
     * but did not open a GitHub issue (e.g. the hourly issue quota was hit).
     * On a failure, [errorKind] explains what went wrong so the UI can show
     * tailored copy, and [retryAfterSeconds] carries the worker's `Retry-After`
     * hint for a 429.
     */
    data class Result(
        val ok: Boolean,
        val issueNumber: Int?,
        val issueUrl: String?,
        val feedbackId: String?,
        val errorKind: ErrorKind = ErrorKind.NONE,
        val retryAfterSeconds: Int? = null,
    )

    /**
     * POST a feedback submission as multipart form data. Runs on
     * [Dispatchers.IO] and never throws — a network failure returns `ok = false`.
     *
     * @param onProgress invoked with the upload fraction in `0f..1f` as bytes
     *   are written, so the UI can show a determinate progress bar. Reaches
     *   `1f` once the request body is fully sent (the worker is then
     *   transcribing + opening the issue). Called off the main thread.
     */
    suspend fun upload(
        category: FeedbackCategory,
        note: String,
        context: Map<String, String>,
        recording: FeedbackRecording?,
        onProgress: (Float) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "category",
                if (category == FeedbackCategory.BUG) "bug" else "idea",
            )
            .addFormDataPart("context", JSONObject(context).toString())
            .apply {
                if (note.isNotBlank()) addFormDataPart("text", note)
                recording?.video?.let {
                    addFormDataPart(
                        "video",
                        "screen.mp4",
                        it.asRequestBody("video/mp4".toMediaTypeOrNull()),
                    )
                }
                recording?.audio?.let {
                    addFormDataPart(
                        "audio",
                        "audio.m4a",
                        it.asRequestBody("audio/mp4".toMediaTypeOrNull()),
                    )
                }
            }
            .build()

        val request = Request.Builder()
            .url(endpoint)
            .post(ProgressRequestBody(multipart, onProgress))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                parseResponse(response)
            }
        } catch (@Suppress("SwallowedException") e: Exception) { // network error → return typed error result
            Result(false, null, null, null, ErrorKind.NETWORK)
        }
    }

    /**
     * Map an HTTP [Response] to a [Result]. Extracted so the status-code →
     * [ErrorKind] mapping and the JSON parsing are unit-testable. Reads (and
     * therefore consumes) the response body.
     */
    internal fun parseResponse(response: Response): Result {
        if (!response.isSuccessful) {
            val kind = when (response.code) {
                413 -> ErrorKind.TOO_LARGE
                429 -> ErrorKind.RATE_LIMITED
                in 500..599 -> ErrorKind.SERVER
                else -> ErrorKind.OTHER
            }
            val retryAfter = response.header("Retry-After")
                ?.trim()
                ?.toIntOrNull()
                ?.takeIf { it in 1..3600 }
            return Result(false, null, null, null, kind, retryAfter)
        }
        val json = runCatching {
            JSONObject(response.body.string())
        }.getOrNull()
        if (json == null) {
            // A 200 with an unparseable body — treat as a failure rather than a
            // silent success that strands the user with no issue link.
            return Result(false, null, null, null, ErrorKind.OTHER)
        }
        return Result(
            ok = json.optBoolean("ok"),
            issueNumber = json.optInt("issue", 0).takeIf { it > 0 },
            issueUrl = json.optString("url").takeIf { it.isNotBlank() },
            feedbackId = json.optString("id").takeIf { it.isNotBlank() },
        )
    }

    /**
     * Wraps a [RequestBody] to report how much of it has been written, so the
     * feedback UI can show a determinate upload progress bar. A screen recording
     * is the bulk of the payload, so byte-level progress is meaningful here.
     */
    private class ProgressRequestBody(
        private val delegate: RequestBody,
        private val onProgress: (Float) -> Unit,
    ) : RequestBody() {

        override fun contentType(): MediaType? = delegate.contentType()

        override fun contentLength(): Long = delegate.contentLength()

        override fun writeTo(sink: BufferedSink) {
            val total = contentLength()
            if (total <= 0L) {
                // Length unknown — fall through without progress reporting.
                delegate.writeTo(sink)
                onProgress(1f)
                return
            }
            val counting = CountingSink(sink, total, onProgress)
            val buffered = counting.buffer()
            delegate.writeTo(buffered)
            buffered.flush()
            onProgress(1f)
        }
    }

    /** A [ForwardingSink] that reports the running fraction of [total] written. */
    internal class CountingSink(
        delegate: Sink,
        private val total: Long,
        private val onProgress: (Float) -> Unit,
    ) : ForwardingSink(delegate) {

        private var written = 0L

        override fun write(source: Buffer, byteCount: Long) {
            super.write(source, byteCount)
            written += byteCount
            onProgress((written.toFloat() / total).coerceIn(0f, 1f))
        }
    }
}

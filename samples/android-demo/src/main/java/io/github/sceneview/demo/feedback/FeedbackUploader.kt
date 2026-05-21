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
     * `samples/android-demo/build.gradle`. The worker is not deployed yet; until
     * the maintainer sets the real URL there, this points at the planned
     * workers.dev subdomain.
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

    /**
     * Outcome of an upload. [ok] is true when the worker accepted the feedback.
     * [issueNumber] / [issueUrl] are null when the worker stored the feedback
     * but did not open a GitHub issue (e.g. the hourly issue quota was hit).
     */
    data class Result(
        val ok: Boolean,
        val issueNumber: Int?,
        val issueUrl: String?,
        val feedbackId: String?,
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
                if (!response.isSuccessful) {
                    return@withContext Result(false, null, null, null)
                }
                val json = runCatching {
                    JSONObject(response.body.string())
                }.getOrNull()
                Result(
                    ok = json?.optBoolean("ok") == true,
                    issueNumber = json?.optInt("issue", 0)?.takeIf { it > 0 },
                    issueUrl = json?.optString("url")?.takeIf { it.isNotBlank() },
                    feedbackId = json?.optString("id")?.takeIf { it.isNotBlank() },
                )
            }
        } catch (e: Exception) {
            Result(false, null, null, null)
        }
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
    private class CountingSink(
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

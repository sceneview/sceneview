package io.github.sceneview.demo.feedback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Uploads feedback submissions to the SceneView feedback worker. */
object FeedbackUploader {

    private const val ENDPOINT =
        "https://sceneview-feedback.mcp-tools-lab.workers.dev/v1/feedback"

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
     */
    suspend fun upload(
        category: FeedbackCategory,
        note: String,
        context: Map<String, String>,
        recording: FeedbackRecording?,
    ): Result = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
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

        val request = Request.Builder().url(ENDPOINT).post(body).build()
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
}

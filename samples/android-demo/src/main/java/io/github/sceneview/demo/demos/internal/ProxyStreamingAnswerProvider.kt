package io.github.sceneview.demo.demos.internal

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync
import okio.BufferedSink
import java.util.concurrent.TimeUnit

internal class ProxyStreamingAnswerProvider(
    baseUrl: String,
    private val clientTag: String,
    private val client: OkHttpClient = defaultClient,
) : StreamingAnswerProvider {
    private val endpoint = baseUrl.toHttpUrl().newBuilder()
        .encodedPath("/ask").query(null).fragment(null).build()

    init {
        require(endpoint.isHttps || endpoint.host in listOf("localhost", "127.0.0.1", "::1")) {
            "The Ask proxy requires HTTPS"
        }
        require(endpoint.username.isEmpty() && endpoint.password.isEmpty())
        require(clientTag.isNotBlank())
    }

    override fun stream(question: String, context: AskContext): Flow<AnswerChunk> =
        stream(question, AskLiveContext(distanceAheadMeters = context.distanceAheadMeters))

    override fun stream(question: String, context: AskLiveContext): Flow<AnswerChunk> =
        streamAfterUpload(question, context) { }

    override fun streamAfterUpload(
        question: String,
        context: AskLiveContext,
        onBodyWritten: () -> Unit,
    ): Flow<AnswerChunk> = channelFlow {
        if (question.isBlank() || question.length > 200) {
            send(AnswerChunk.Failed("Question must contain 1-200 characters"))
            return@channelFlow
        }
        val json = buildJsonObject {
            put("question", question)
            put("context", context.toJson())
        }
        val delegate = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val body = object : RequestBody() {
            override fun contentType() = delegate.contentType()
            override fun contentLength() = delegate.contentLength()
            override fun writeTo(sink: BufferedSink) {
                delegate.writeTo(sink)
                sink.flush()
                onBodyWritten()
            }
        }
        val request = Request.Builder().url(endpoint)
            .header("X-Ask-Client", clientTag)
            .header("Accept", "text/event-stream")
            .post(body)
            .build()
        val call = client.newCall(request)
        // executeAsync handles cancellation until headers arrive; this watcher covers body reads.
        val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) {
                call.executeAsync().use { response ->
                    if (!response.isSuccessful) {
                        send(AnswerChunk.Failed("Ask proxy returned HTTP ${response.code}"))
                    } else if (response.header("Content-Type")?.substringBefore(';')?.trim() != "text/event-stream") {
                        send(AnswerChunk.Failed("Ask proxy did not return an event stream"))
                    } else {
                        response.body.charStream().use { reader ->
                            readEvents { chars -> reader.read(chars) }
                        }
                    }
                }
            }
        } finally {
            call.cancel()
            cancellation.cancel()
        }
    }.buffer(0).catch {
        currentCoroutineContext().ensureActive()
        emit(AnswerChunk.Failed("Live answer unavailable"))
    }

    private suspend fun ProducerScope<AnswerChunk>.readEvents(read: (CharArray) -> Int) {
        val parser = AskSseParser()
        val buffer = CharArray(1024)
        var received = 0
        while (!parser.terminal) {
            currentCoroutineContext().ensureActive()
            val count = read(buffer)
            if (count < 0) break
            received += count
            require(received <= 32_768) { "Answer stream too large" }
            parser.feed(String(buffer, 0, count)).forEach { send(it) }
        }
        if (!parser.terminal) send(AnswerChunk.Failed("Answer stream ended before completion"))
    }

    private companion object {
        val defaultClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}

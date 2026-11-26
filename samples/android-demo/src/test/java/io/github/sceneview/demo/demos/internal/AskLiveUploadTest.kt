package io.github.sceneview.demo.demos.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okio.BufferedSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class AskLiveUploadTest {
    private val fallback = StreamingAnswerProvider { _, _ -> flow {
        emit(AnswerChunk.Text("Script"))
        emit(AnswerChunk.Done(Doodle.None, ""))
    } }

    @Test fun `2500 ms fallback starts only after a slow upload finishes`() = runTest {
        val primary = object : StreamingAnswerProvider {
            override fun stream(question: String, context: AskContext): Flow<AnswerChunk> = error("Use upload path")
            override fun streamAfterUpload(question: String, context: AskLiveContext, onBodyWritten: () -> Unit) = flow<AnswerChunk> {
                delay(4_000)
                onBodyWritten()
                awaitCancellation()
            }
        }
        val chunks = mutableListOf<AnswerChunk>()
        val job = launch {
            FallbackStreamingAnswerProvider(primary, fallback, nowMillis = { testScheduler.currentTime })
                .stream("Hello", AskLiveContext()).toList(chunks)
        }
        advanceTimeBy(6_499)
        assertTrue(chunks.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        job.join()
        assertEquals(6_500L, testScheduler.currentTime)
        assertEquals(AnswerChunk.Text("Script"), chunks[1])
    }

    @Test fun `real transport posts the full snapshot and signals only after body write`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
                .body("data: {\"t\":\"Live \"}\n\ndata: {\"done\":true,\"doodle\":\"none\",\"emphasis\":\"\"}\n\n").build())
            var wroteBody = false
            val client = OkHttpClient.Builder().addNetworkInterceptor { chain ->
                val original = chain.request()
                val body = original.body!!
                chain.proceed(original.newBuilder().post(object : RequestBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun writeTo(sink: BufferedSink) {
                        Thread.sleep(300)
                        body.writeTo(sink)
                        wroteBody = true
                    }
                }).build())
            }.build()
            val context = AskLiveContext(
                distanceAheadMeters = 0.7f, surface = AskLiveSurface("wall", 2f, 3f),
                cameraHeightMeters = 1.6f, localTime = "2026-11-25T12:34:56+01:00", locale = "fr-FR",
                image = AskLiveImage("/9j/2Q=="),
            )
            val proxy = ProxyStreamingAnswerProvider(server.url("/").toString(), "test-client", client)
            val chunks = FallbackStreamingAnswerProvider(proxy, fallback, startupTimeoutMillis = 150)
                .stream("What is visible?", context).toList()
            assertTrue(wroteBody)
            assertFalse(chunks.any { it is AnswerChunk.Failed })
            assertEquals(AnswerChunk.Text("Live "), chunks.first())
            val json = Json.parseToJsonElement(server.takeRequest(2, TimeUnit.SECONDS)!!.body!!.utf8()).jsonObject
            assertEquals(context.toJson(), json["context"])
            assertEquals("What is visible?", json["question"]!!.jsonPrimitive.content)
        }
    }
}

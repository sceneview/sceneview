package io.github.sceneview.demo.demos.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class AskProxyStreamingTest {
    @Test fun `posts context and client tag and parses an event stream`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder()
                .addHeader("Content-Type", "text/event-stream; charset=utf-8")
                .body(": ping\n\ndata: {\"t\":\"Hi \"}\n\n" +
                    "data: {\"done\":true,\"doodle\":\"none\",\"emphasis\":\"\"}\n\n")
                .build())
            val chunks = ProxyStreamingAnswerProvider(server.url("/").toString(), "test-client")
                .stream("Hello", AskContext(2.5f)).toList()
            assertEquals(listOf(AnswerChunk.Text("Hi "), AnswerChunk.Done(Doodle.None, "")), chunks)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/ask", request.url.encodedPath)
            assertEquals("test-client", request.headers["X-Ask-Client"])
            assertTrue(request.body!!.utf8().contains("\"distanceAheadMeters\":2.5"))
        }
    }

    @Test fun `EOF without done and HTTP errors emit failure`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
                .body("data: {\"t\":\"Partial \"}\n\n").build())
            server.enqueue(MockResponse.Builder().code(429).build())
            val provider = ProxyStreamingAnswerProvider(server.url("/").toString(), "test-client")
            assertTrue(provider.stream("Hello", AskContext()).toList().last() is AnswerChunk.Failed)
            assertEquals(
                listOf(AnswerChunk.Failed("Ask proxy returned HTTP 429")),
                provider.stream("Hello", AskContext()).toList(),
            )
        }
    }

    @Test fun `cancellation after headers aborts a blocked body read`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/event-stream")
                .body("data: {\"t\":\"late\"}\n\n").bodyDelay(10, TimeUnit.SECONDS).build())
            val headers = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun responseHeadersEnd(call: Call, response: okhttp3.Response) {
                    headers.complete(Unit)
                }
                override fun canceled(call: Call) { cancelled.complete(Unit) }
            }).build()
            val chunks = mutableListOf<AnswerChunk>()
            val job = launch {
                ProxyStreamingAnswerProvider(server.url("/").toString(), "test-client", client)
                    .stream("Hello", AskContext()).toList(chunks)
            }
            withTimeout(2_000) { headers.await() }
            withTimeout(2_000) { job.cancelAndJoin(); cancelled.await() }
            assertTrue(chunks.isEmpty())
        }
    }
}

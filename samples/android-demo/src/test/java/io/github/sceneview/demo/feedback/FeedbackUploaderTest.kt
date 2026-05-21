package io.github.sceneview.demo.feedback

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.blackholeSink
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [FeedbackUploader] — the HTTP-status → [FeedbackUploader.ErrorKind]
 * mapping, success-body parsing, and the upload-progress byte accounting
 * (#1930 review).
 *
 * Runs under Robolectric so `org.json` resolves to a real implementation
 * rather than the unmocked Android stub.
 */
@RunWith(RobolectricTestRunner::class)
class FeedbackUploaderTest {

    private fun response(
        code: Int,
        body: String = "",
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val builder = Response.Builder()
            .request(Request.Builder().url("https://example.test/v1/feedback").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("msg")
            .body(body.toResponseBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    // ── Status-code → ErrorKind mapping ──────────────────────────────────────

    @Test
    fun `413 maps to TOO_LARGE`() {
        val result = FeedbackUploader.parseResponse(response(413, """{"error":"x"}"""))
        assertFalse(result.ok)
        assertEquals(FeedbackUploader.ErrorKind.TOO_LARGE, result.errorKind)
    }

    @Test
    fun `429 maps to RATE_LIMITED and reads Retry-After`() {
        val result = FeedbackUploader.parseResponse(
            response(429, headers = mapOf("Retry-After" to "45")),
        )
        assertFalse(result.ok)
        assertEquals(FeedbackUploader.ErrorKind.RATE_LIMITED, result.errorKind)
        assertEquals(45, result.retryAfterSeconds)
    }

    @Test
    fun `a 5xx maps to SERVER`() {
        val result = FeedbackUploader.parseResponse(response(503))
        assertEquals(FeedbackUploader.ErrorKind.SERVER, result.errorKind)
    }

    @Test
    fun `a generic 4xx maps to OTHER`() {
        val result = FeedbackUploader.parseResponse(response(400))
        assertEquals(FeedbackUploader.ErrorKind.OTHER, result.errorKind)
    }

    @Test
    fun `an absurd Retry-After is ignored`() {
        val result = FeedbackUploader.parseResponse(
            response(429, headers = mapOf("Retry-After" to "999999")),
        )
        assertNull(result.retryAfterSeconds)
    }

    // ── Success-body parsing ─────────────────────────────────────────────────

    @Test
    fun `a successful response parses issue number and url`() {
        val result = FeedbackUploader.parseResponse(
            response(
                200,
                """{"ok":true,"issue":1234,"url":"https://gh/i/1234","id":"fb-1"}""",
            ),
        )
        assertTrue(result.ok)
        assertEquals(1234, result.issueNumber)
        assertEquals("https://gh/i/1234", result.issueUrl)
        assertEquals("fb-1", result.feedbackId)
        assertEquals(FeedbackUploader.ErrorKind.NONE, result.errorKind)
    }

    @Test
    fun `a 200 that stored feedback but opened no issue has a null issue number`() {
        val result = FeedbackUploader.parseResponse(
            response(200, """{"ok":true,"id":"fb-2"}"""),
        )
        assertTrue(result.ok)
        assertNull(result.issueNumber)
        assertNull(result.issueUrl)
        assertEquals("fb-2", result.feedbackId)
    }

    @Test
    fun `a 200 with an unparseable body is treated as a failure`() {
        val result = FeedbackUploader.parseResponse(response(200, "<html>not json</html>"))
        assertFalse(result.ok)
        assertEquals(FeedbackUploader.ErrorKind.OTHER, result.errorKind)
    }

    // ── CountingSink byte accounting ─────────────────────────────────────────

    @Test
    fun `CountingSink reports the running fraction up to 1f`() {
        val total = 1_000L
        val fractions = mutableListOf<Float>()
        val sink = FeedbackUploader.CountingSink(
            blackholeSink(),
            total,
        ) { fractions += it }
        val buffered = sink.buffer()

        // Write the payload in four equal chunks.
        repeat(4) {
            buffered.write(ByteArray(250))
            buffered.flush()
        }

        assertTrue("progress was reported", fractions.isNotEmpty())
        assertEquals("reaches 100%", 1.0f, fractions.last(), 0.0001f)
        // Progress is monotonically non-decreasing.
        for (i in 1 until fractions.size) {
            assertTrue("monotonic", fractions[i] >= fractions[i - 1])
        }
    }

    @Test
    fun `CountingSink never reports more than 1f even past the declared total`() {
        val fractions = mutableListOf<Float>()
        val sink = FeedbackUploader.CountingSink(blackholeSink(), 100L) { fractions += it }
        val buffered = sink.buffer()

        buffered.write(ByteArray(500)) // 5x the declared total
        buffered.flush()

        assertTrue(fractions.all { it <= 1.0f })
    }

    @Test
    fun `CountingSink forwards every byte to the delegate`() {
        val captured = Buffer()
        val sink = FeedbackUploader.CountingSink(captured, 8L) {}
        val buffered = sink.buffer()

        buffered.write(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        buffered.flush()

        assertEquals(8L, captured.size)
    }
}

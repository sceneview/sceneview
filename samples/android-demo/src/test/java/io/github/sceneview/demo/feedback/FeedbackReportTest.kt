package io.github.sceneview.demo.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class FeedbackReportTest {
    @Test
    fun `description rejects blank and fewer than ten non-whitespace characters`() {
        listOf("", " \t\n\u00a0\u2003", "Too short", " 12345\n6789\t").forEach {
            assertFalse("Accepted '$it'", isFeedbackDescriptionValid(it))
        }
    }

    @Test
    fun `description accepts the boundary and sentences in any language`() {
        listOf("a b c d e f g h i j", "The model goes black.", "模型加载完成后屏幕变黑了。").forEach {
            assertTrue("Rejected '$it'", isFeedbackDescriptionValid(it))
        }
    }

    @Test
    fun `empty log stays empty and a single line stays intact`() {
        assertEquals(emptyList<String>(), collapseFeedbackLog(emptyList()))
        assertEquals(listOf("a stack trace line"), collapseFeedbackLog(listOf("a stack trace line")))
    }

    @Test
    fun `repeats ignore timestamps and thread ids but keep the first and last times`() {
        val first = "11-19 12:00:00.123  1234  5678 W Filament: draw skipped"
        val lines = listOf(
            first,
            "11-19 12:00:00.456  1234  9999 W Filament: draw skipped",
            "11-19 12:00:01.789  1234  5678 W Filament: draw skipped",
        )
        assertEquals(listOf("$first ×3 (last at 12:00:01.789)"), collapseFeedbackLog(lines))
    }

    @Test
    fun `only consecutive messages of the same tag and priority collapse`() {
        val lines = listOf(
            "12:00:00.000 W Filament: draw skipped",
            "12:00:00.001 E Filament: draw skipped",
            "12:00:00.002 W SceneView: draw skipped",
            "12:00:00.003 W Filament: texture missing",
            "12:00:00.004 W Filament: draw skipped",
        )
        assertEquals(lines, collapseFeedbackLog(lines))
        assertEquals(listOf("trace ×2", "other", "trace"), collapseFeedbackLog(listOf("trace", "trace", "other", "trace")))
    }

    @Test
    fun `only the known frame fence noise is dropped`() {
        val useful = listOf(
            "12:00:01.000 E FrameEvents: another error",
            "12:00:02.000 E Renderer: updateAcquireFence: Did not find frame.",
        )
        val noise = (1..81).map { "11-19 12:00:00.123  1234  5678 E FrameEvents: updateAcquireFence: Did not find frame." }
        assertEquals(useful, collapseFeedbackLog(noise + useful))
        assertEquals(emptyList<String>(), collapseFeedbackLog(listOf(
            "20:53:31.628 E FrameEvents: updateAcquireFence: Did not find frame.",
        )))
    }

    @Test
    fun `both report formats collapse before selecting their log window`() {
        val info = BugReportInfo(
            metadata = emptyMap(),
            logcat = listOf("important earlier error") + List(100) { "repeating warning" },
        )
        val body = formatIssueBody(info, "The model goes black.", maxLogcatLines = 2)
        val file = formatReportFile(info, "The model goes black.")
        listOf(body, file).forEach {
            assertTrue(it.contains("important earlier error"))
            assertTrue(it.contains("repeating warning ×100"))
            assertTrue(it.contains("last 2 lines"))
        }
        assertTrue(formatShareText(info, "The model goes black.").contains("app log (2 lines)"))
    }

    @Test
    fun `noise alone does not produce an attached log section`() {
        val info = BugReportInfo(emptyMap(), listOf("12:00:00.000 E FrameEvents: updateAcquireFence: Did not find frame."))
        assertFalse(formatIssueBody(info, "The model goes black.").contains("<details>"))
        assertFalse(formatReportFile(info, "The model goes black.").contains("Recent app log"))
        assertFalse(formatShareText(info, "The model goes black.").contains("app log ("))
    }

    @Test
    fun `every category is carried in every destination without privileged label parameters`() {
        FeedbackCategory.entries.forEach { category ->
            val info = BugReportInfo(emptyMap(), emptyList(), category = category)
            val note = "The model goes black."
            val url = buildGitHubIssueUrl(info, note)
            val body = URLDecoder.decode(url.substringAfter("&body="), "UTF-8")
            assertFalse(url.contains("&labels="))
            assertTrue(body.contains("| Category | ${category.reportValue} |"))
            assertTrue(formatReportFile(info, note).contains("Category: ${category.reportValue}"))
            assertTrue(formatShareText(info, note).contains("Category: ${category.reportValue}"))
        }
    }

    @Test
    fun `category survives the URL fallback for a very long description`() {
        val info = BugReportInfo(emptyMap(), emptyList(), category = FeedbackCategory.Question)
        val url = buildGitHubIssueUrl(info, "x".repeat(50_000))
        assertTrue(url.length <= ISSUE_URL_MAX_LENGTH)
        assertTrue(URLDecoder.decode(url.substringAfter("&body="), "UTF-8").contains("| Category | Question |"))
    }
}

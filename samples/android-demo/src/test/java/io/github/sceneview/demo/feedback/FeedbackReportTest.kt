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
        listOf("a b c d e f g h i j", "The model goes black.", "模型加载后黑屏", "モデルが黒い", "모델이검게보임").forEach {
            assertTrue("Rejected '$it'", isFeedbackDescriptionValid(it))
        }
    }

    @Test
    fun `CJK scripts count as two at the boundary including supplementary ideographs`() {
        listOf("模", "あ", "ア", "ﾓ", "모", String(Character.toChars(0x20000))).forEach { character ->
            assertFalse("Accepted four '$character'", isFeedbackDescriptionValid(character.repeat(4)))
            assertTrue("Rejected five '$character'", isFeedbackDescriptionValid(character.repeat(5)))
        }
        assertFalse(isFeedbackDescriptionValid("模型 a b c d e"))
        assertTrue(isFeedbackDescriptionValid("模型 a b c d e f"))
        assertFalse(isFeedbackDescriptionValid("😀".repeat(5)))
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
            assertTrue(formatReportFile(info, note).startsWith("SceneView demo — feedback"))
            assertTrue(formatShareText(info, note).startsWith("SceneView demo — feedback"))
            assertTrue(body.contains("_Sent from the in-app feedback form._"))
            assertEquals("[Demo app] Feedback", formatReportTitle(info, ""))
        }
    }

    @Test
    fun `category survives the URL fallback for a very long description`() {
        val info = BugReportInfo(emptyMap(), emptyList(), category = FeedbackCategory.Question)
        val url = buildGitHubIssueUrl(info, "x".repeat(50_000))
        assertTrue(url.length <= ISSUE_URL_MAX_LENGTH)
        assertTrue(URLDecoder.decode(url.substringAfter("&body="), "UTF-8").contains("| Category | Question |"))
    }

    @Test
    fun `URL fallback fits encoded CJK and Cyrillic descriptions without splitting code points`() {
        val info = BugReportInfo(
            metadata = mapOf("Screen" to "Demo · model-viewer"),
            logcat = listOf("a diagnostic line"),
            arTracking = listOf("an AR diagnostic line"),
            category = FeedbackCategory.Question,
        )
        listOf("模型加载后黑屏", "モデルが黒い", "모델이검게보임", "Модель", String(Character.toChars(0x20000))).forEach { text ->
            val note = text.repeat(2000)
            val url = buildGitHubIssueUrl(info, note)
            val body = URLDecoder.decode(url.substringAfter("&body="), "UTF-8")
            val prefix = body.substringBefore("\n\n### Context")
            assertTrue("url length ${url.length} for '$text'", url.length <= ISSUE_URL_MAX_LENGTH)
            assertTrue(prefix.isNotEmpty())
            assertTrue(note.startsWith(prefix))
            assertFalse(Character.isHighSurrogate(prefix.last()))
            assertTrue(body.contains("| Screen | Demo · model-viewer |"))
            assertTrue(body.contains("| Category | Question |"))
            assertFalse(body.contains("<details>"))
        }
    }

    @Test
    fun `URL fallback remains bounded when metadata alone exceeds the budget`() {
        val info = BugReportInfo(mapOf("Device" to "模".repeat(2000)), emptyList(), category = FeedbackCategory.FeatureRequest)
        val url = buildGitHubIssueUrl(info, "Please add a new model.")
        val body = URLDecoder.decode(url.substringAfter("&body="), "UTF-8")
        assertTrue("url length ${url.length}", url.length <= ISSUE_URL_MAX_LENGTH)
        assertTrue(body.startsWith("Please add a new model."))
        assertTrue(body.contains("| Category | Feature request |"))
    }

    @Test
    fun `URL line ceiling uses collapsed AR tracking size so the entire general log can fit`() {
        val info = BugReportInfo(
            metadata = emptyMap(),
            logcat = (1..ISSUE_LOGCAT_MAX_LINES).map { "line $it" },
            arTracking = (1..40).map { "12:00:00.000 I SceneView: AR tracking: state=TRACKING reason=NONE" },
        )
        val url = buildGitHubIssueUrl(info, "The model goes black.")
        val body = URLDecoder.decode(url.substringAfter("&body="), "UTF-8")
        assertTrue("url length ${url.length}", url.length <= ISSUE_URL_MAX_LENGTH)
        assertTrue(body.contains("AR tracking (last 1 lines)"))
        assertTrue(body.contains("App log (last ${ISSUE_LOGCAT_MAX_LINES - 1} lines)"))
        assertTrue(body.contains("line 2\n"))
        assertTrue(body.contains("line $ISSUE_LOGCAT_MAX_LINES\n"))
    }

    @Test
    fun `collapsed AR tracking does not skip a small fitting general log window`() {
        val info = BugReportInfo(
            metadata = emptyMap(),
            logcat = (1..3).map { "message $it: " + "x".repeat(120) },
            arTracking = List(40) { "12:00:00.000 I SceneView: AR tracking: state=TRACKING reason=NONE" },
        )
        val bare = info.copy(logcat = emptyList())
        val room = ISSUE_URL_MAX_LENGTH - buildGitHubIssueUrl(bare, "Stuck\n").length - 400
        val url = buildGitHubIssueUrl(info, "Stuck\n" + "y".repeat(room))
        val body = URLDecoder.decode(url.substringAfter("&body="), "UTF-8")
        assertTrue("url length ${url.length}", url.length <= ISSUE_URL_MAX_LENGTH)
        assertTrue(body.contains("AR tracking (last 1 lines)"))
        assertTrue(body.contains("message 3:"))
        assertFalse(body.contains("message 1:"))
    }
}

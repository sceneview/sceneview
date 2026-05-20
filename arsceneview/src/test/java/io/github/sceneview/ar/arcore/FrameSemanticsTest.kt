package io.github.sceneview.ar.arcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-contract tests for the Scene Semantics Frame accessors landed in #1730.
 *
 * The underlying `com.google.ar.core.Frame` is a JNI-bound class — its methods cannot be
 * mocked under pure-JVM tests without a heavyweight bytecode-rewrite mocking framework
 * (the arsceneview test deps are just JUnit 4 + Robolectric — see `arsceneview/build.gradle`).
 *
 * We instead pin the source-level contract for the three accessors. This catches the most
 * likely regressions — someone refactoring the catch block to swallow a wider exception,
 * accidentally dropping the `Image` lifecycle KDoc, or losing the "return 0f on
 * NotYetAvailable" guarantee for [semanticLabelFraction].
 *
 * The behavioural contract itself (right value forwarded; right exception swallowed) is
 * exercised on-device through `ARSceneSemanticsDemo` — see `samples/android-demo`.
 */
class FrameSemanticsTest {

    private val frameFile = File("src/main/java/io/github/sceneview/ar/arcore/Frame.kt")

    private val frameSource: String by lazy {
        assertTrue(
            "Expected to find ${frameFile.absolutePath} — JVM test must run from arsceneview " +
                "module root.",
            frameFile.exists()
        )
        frameFile.readText()
    }

    @Test
    fun `Frame_kt exposes semanticImage accessor`() {
        assertTrue(
            "Frame.kt must declare `fun Frame.semanticImage(): Image?` — the public accessor " +
                "for ARCore Scene Semantics (#1730).",
            Regex("""fun\s+Frame\.semanticImage\s*\(\s*\)\s*:\s*Image\?""")
                .containsMatchIn(frameSource)
        )
    }

    @Test
    fun `Frame_kt exposes semanticConfidenceImage accessor`() {
        assertTrue(
            "Frame.kt must declare `fun Frame.semanticConfidenceImage(): Image?` (#1730).",
            Regex("""fun\s+Frame\.semanticConfidenceImage\s*\(\s*\)\s*:\s*Image\?""")
                .containsMatchIn(frameSource)
        )
    }

    @Test
    fun `Frame_kt exposes semanticLabelFraction accessor returning Float`() {
        assertTrue(
            "Frame.kt must declare " +
                "`fun Frame.semanticLabelFraction(label: SemanticLabel): Float` (#1730).",
            Regex(
                """fun\s+Frame\.semanticLabelFraction\s*\(\s*label\s*:\s*SemanticLabel\s*\)\s*:\s*Float"""
            ).containsMatchIn(frameSource)
        )
    }

    @Test
    fun `semantic accessors swallow NotYetAvailableException`() {
        // Each of the three accessors must wrap the JNI call in `try { ... } catch
        // (_: NotYetAvailableException) { <safe-default> }` — failing this contract would
        // break the established "first frame after resume returns the safe default" pattern
        // (see depthImage / cameraImage). We pin three independent matches so a regression in
        // any one of them is surfaced individually.
        val semanticImageBody = extractAccessorBody("semanticImage")
        assertTrue(
            "`fun Frame.semanticImage()` must catch NotYetAvailableException — observed body: " +
                semanticImageBody,
            "NotYetAvailableException" in semanticImageBody
        )

        val semanticConfidenceImageBody = extractAccessorBody("semanticConfidenceImage")
        assertTrue(
            "`fun Frame.semanticConfidenceImage()` must catch NotYetAvailableException — " +
                "observed body: $semanticConfidenceImageBody",
            "NotYetAvailableException" in semanticConfidenceImageBody
        )

        val semanticLabelFractionBody = extractAccessorBody("semanticLabelFraction")
        assertTrue(
            "`fun Frame.semanticLabelFraction()` must catch NotYetAvailableException so the " +
                "safe-default 0f is returned when semantics are not yet available — observed " +
                "body: $semanticLabelFractionBody",
            "NotYetAvailableException" in semanticLabelFractionBody
        )
        assertTrue(
            "`fun Frame.semanticLabelFraction()` must return 0f in the NotYetAvailable branch — " +
                "absence of a label is the natural placement-rule default (e.g. \"place on " +
                "TERRAIN only\" should not fire while waiting for the first semantic frame). " +
                "Observed body: $semanticLabelFractionBody",
            Regex("""NotYetAvailableException\s*\)\s*\{\s*0f""")
                .containsMatchIn(semanticLabelFractionBody)
        )
    }

    @Test
    fun `semanticImage KDoc warns about Image lifecycle`() {
        // The `Image` returned by ARCore is caller-owned with a shallow pool — failing to
        // close it leaks a native handle and the next acquire() throws ResourceExhausted.
        // The contract is established in the KDocs of `cameraImage` / `depthImage` so we
        // pin it for the new accessor at the source level (the matc-style ABI invariant for
        // KDoc-only contracts).
        val kdocAndAccessor = extractKdocAndAccessor("semanticImage")
        assertTrue(
            "`fun Frame.semanticImage()` KDoc must warn the caller about Image.close() / " +
                "`use { }` — observed: $kdocAndAccessor",
            kdocAndAccessor.contains("caller-owned", ignoreCase = true) ||
                kdocAndAccessor.contains("close", ignoreCase = true)
        )
    }

    @Test
    fun `the twelve ARCore SemanticLabel ordinal values exist in the SDK`() {
        // The label set is part of the public API contract; if Google ever adds a 13th class
        // we'd want llms.txt to be regenerated. This pins the count via the public enum so a
        // future ARCore upgrade triggers a JVM-test failure rather than a silent drift.
        val labels = com.google.ar.core.SemanticLabel.values()
        assertEquals(
            "ARCore declares 12 outdoor SemanticLabel classes — if this count changes (Google " +
                "adds INDOOR / SAND / etc.) bump llms.txt and the ARSceneSemanticsDemo HUD.",
            12,
            labels.size
        )
        assertNotNull(labels.firstOrNull { it.name == "SKY" })
        assertNotNull(labels.firstOrNull { it.name == "TERRAIN" })
        assertNotNull(labels.firstOrNull { it.name == "PERSON" })
        assertNotNull(labels.firstOrNull { it.name == "UNLABELED" })
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────

    /**
     * Returns the substring of [frameSource] starting at the accessor signature and ending at
     * the next blank line. Frame.kt uses single-expression bodies (`= try { … } catch …`) so the
     * "end of body" cue is the blank line, not a closing brace.
     */
    private fun extractAccessorBody(funName: String): String {
        val sigIdx = frameSource.indexOf("fun Frame.$funName(")
        if (sigIdx < 0) {
            throw AssertionError("Could not find `fun Frame.$funName(` in Frame.kt")
        }
        // Walk forward to the next blank line (which terminates the expression body or the
        // accessor block). Fall back to end-of-file when the accessor is the last in the file.
        val endIdx = frameSource.indexOf("\n\n", sigIdx).let {
            if (it < 0) frameSource.length else it
        }
        return frameSource.substring(sigIdx, endIdx)
    }

    /** Returns the KDoc block + accessor signature for [funName]. */
    private fun extractKdocAndAccessor(funName: String): String {
        // Walk backwards from the accessor signature to the nearest blank line — the KDoc
        // for a public extension lives directly above it.
        val sigIdx = frameSource.indexOf("fun Frame.$funName(")
        if (sigIdx < 0) throw AssertionError("Could not find `fun Frame.$funName(` in Frame.kt")
        val precedingBlank = frameSource.lastIndexOf("\n\n", sigIdx)
        val start = if (precedingBlank < 0) 0 else precedingBlank
        val end = frameSource.indexOf("\n\n", sigIdx).let { if (it < 0) frameSource.length else it }
        return frameSource.substring(start, end)
    }
}

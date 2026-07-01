package io.github.sceneview.ar

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-text pins for the typed `Config.*Mode` DSL params landed in #1766.
 *
 * Verifies:
 *  - `ARSceneView` exposes every new typed param with the documented default.
 *  - Each typed param is applied to the [com.google.ar.core.Config] BEFORE the user-supplied
 *    `sessionConfigurationRef.get()?.invoke(...)` callback — so the callback still wins (the
 *    explicit "escape hatch" semantics #1766 acceptance pins).
 *  - Each typed param has a reactive [androidx.compose.runtime.LaunchedEffect] block that
 *    reconfigures the live session when the param changes (mirrors the `flashMode` reactivity).
 *
 * Same source-grep strategy as [ARCompletenessDefaultsTest] — reflection over a `@Composable`'s
 * synthetic parameters is brittle, strings + regex are robust against formatter wraps.
 */
class ARTypedConfigModesTest {

    private val arSceneFile = File("src/main/java/io/github/sceneview/ar/ARSceneView.kt")

    private val arSceneSource: String by lazy {
        assertTrue(
            "Expected to find ${arSceneFile.absolutePath} — JVM test must run from arsceneview module root.",
            arSceneFile.exists(),
        )
        arSceneFile.readText()
    }

    @Test
    fun `ARSceneView exposes every typed Config Mode parameter with the documented default`() {
        val src = arSceneSource
        // Format: param signature + default. Whitespace tolerant.
        val typedParams = listOf(
            """planeFindingMode\s*:\s*Config\.PlaneFindingMode\s*=\s*Config\.PlaneFindingMode\.HORIZONTAL_AND_VERTICAL""",
            """depthMode\s*:\s*Config\.DepthMode\s*=\s*Config\.DepthMode\.DISABLED""",
            """instantPlacementMode\s*:\s*Config\.InstantPlacementMode\s*=\s*Config\.InstantPlacementMode\.DISABLED""",
            """geospatialMode\s*:\s*Config\.GeospatialMode\s*=\s*Config\.GeospatialMode\.DISABLED""",
            """streetscapeGeometryMode\s*:\s*Config\.StreetscapeGeometryMode\s*=\s*Config\.StreetscapeGeometryMode\.DISABLED""",
            """cloudAnchorMode\s*:\s*Config\.CloudAnchorMode\s*=\s*Config\.CloudAnchorMode\.DISABLED""",
            """augmentedFaceMode\s*:\s*Config\.AugmentedFaceMode\s*=\s*Config\.AugmentedFaceMode\.DISABLED""",
            """imageStabilizationMode\s*:\s*Config\.ImageStabilizationMode\s*=\s*Config\.ImageStabilizationMode\.OFF""",
            """semanticMode\s*:\s*Config\.SemanticMode\s*=\s*Config\.SemanticMode\.DISABLED""",
            """updateMode\s*:\s*Config\.UpdateMode\s*=\s*Config\.UpdateMode\.LATEST_CAMERA_IMAGE""",
            """focusMode\s*:\s*Config\.FocusMode\s*=\s*Config\.FocusMode\.AUTO""",
        )
        typedParams.forEach { pattern ->
            assertTrue(
                "ARSceneView must expose `$pattern` (#1766 acceptance).",
                Regex(pattern).containsMatchIn(src),
            )
        }
    }

    @Test
    fun `each typed Config Mode is applied BEFORE the user sessionConfiguration callback`() {
        val src = arSceneSource
        // Locate the FIRST `session.configure { config -> ... }` block (the one in
        // ARCore.onSessionCreated). Window 6000 chars accommodates all typed params + the user
        // callback line.
        val openIdx = Regex("""session\.configure\s*\{\s*config\s*->""").find(src)?.range?.last
            ?: throw AssertionError("Could not find `session.configure { config -> ` in ARSceneView.kt")
        val window = src.substring(openIdx, (openIdx + 6000).coerceAtMost(src.length))

        val callbackIdx = Regex("""sessionConfigurationRef\.get\(\)\?""").find(window)?.range?.first
            ?: throw AssertionError(
                "Could not locate `sessionConfigurationRef.get()?` user-callback line in the " +
                    "configure block. Window:\n$window",
            )

        // Each typed param must be assigned to `config.<param>` BEFORE the callback.
        val assignments = listOf(
            "config.planeFindingMode",
            "config.depthMode",
            "config.instantPlacementMode",
            "config.geospatialMode",
            "config.streetscapeGeometryMode",
            "config.cloudAnchorMode",
            "config.augmentedFaceMode",
            "config.imageStabilizationMode",
            "config.semanticMode",
            "config.updateMode",
            "config.focusMode",
        )
        assignments.forEach { assignment ->
            val idx = window.indexOf("$assignment =")
            assertTrue(
                "`$assignment =` must appear inside `session.configure { ... }`. Window:\n$window",
                idx >= 0,
            )
            assertTrue(
                "`$assignment =` (idx=$idx) must appear BEFORE the user " +
                    "`sessionConfigurationRef.get()?.invoke(...)` callback (idx=$callbackIdx) so " +
                    "the callback retains its escape-hatch precedence (#1766).",
                idx < callbackIdx,
            )
        }
    }

    @Test
    fun `each typed Config Mode has a reactive LaunchedEffect block`() {
        val src = arSceneSource
        // Each param needs a `LaunchedEffect(<param>) { ... session.configure { config -> config.<param> = <param> } ... }`
        // block so toggling via Compose state reconfigures the running session.
        val reactiveParams = listOf(
            "planeFindingMode",
            "depthMode",
            "instantPlacementMode",
            "geospatialMode",
            "streetscapeGeometryMode",
            "cloudAnchorMode",
            "augmentedFaceMode",
            "imageStabilizationMode",
            "semanticMode",
            "updateMode",
            "focusMode",
        )
        reactiveParams.forEach { name ->
            val pattern = Regex(
                """LaunchedEffect\($name\)\s*\{[^}]*session\.configure\s*\{\s*config\s*->\s*config\.$name\s*=\s*$name\s*\}""",
                RegexOption.DOT_MATCHES_ALL,
            )
            assertTrue(
                "Expected reactive `LaunchedEffect($name) { ... config.$name = $name ... }` " +
                    "block in ARSceneView.kt for the #1766 typed-DSL reactivity contract.",
                pattern.containsMatchIn(src),
            )
        }
    }

    @Test
    fun `each reactive LaunchedEffect skips its own initial-composition run (#2573)`() {
        val src = arSceneSource
        // `flashMode` predates #1766 (#1732) but has the exact same reactivity shape and the same
        // defect, so it's covered here too.
        val gatedParams = listOf(
            "flashMode",
            "planeFindingMode",
            "depthMode",
            "instantPlacementMode",
            "geospatialMode",
            "streetscapeGeometryMode",
            "cloudAnchorMode",
            "augmentedFaceMode",
            "imageStabilizationMode",
            "semanticMode",
            "updateMode",
            "focusMode",
        )
        gatedParams.forEach { name ->
            // 1. A `ChangeGate` must be remembered with the parameter's mount-time value...
            val gateDeclarationPattern = Regex(
                """val\s+${name}Gate\s*=\s*remember\s*\{\s*ChangeGate\($name\)\s*\}""",
            )
            assertTrue(
                "Expected `val ${name}Gate = remember { ChangeGate($name) }` in ARSceneView.kt — " +
                    "without it, `LaunchedEffect($name)`'s mount-time run has no way to tell " +
                    "itself apart from a genuine later change, and silently reverts whatever " +
                    "`sessionConfiguration` set (#2573).",
                gateDeclarationPattern.containsMatchIn(src),
            )

            // 2. ...and `LaunchedEffect($name)` must consult it BEFORE touching the session, so
            // the mount-time run is a no-op.
            val effectOpenIdx = Regex("""LaunchedEffect\($name\)\s*\{""").find(src)?.range?.last
                ?: throw AssertionError("Could not find `LaunchedEffect($name) {` in ARSceneView.kt")
            val effectWindow = src.substring(effectOpenIdx, (effectOpenIdx + 400).coerceAtMost(src.length))

            val guardIdx = effectWindow.indexOf("if (!${name}Gate.shouldApply($name)) return@LaunchedEffect")
            val configureIdx = effectWindow.indexOf("session.configure")

            assertTrue(
                "Expected `if (!${name}Gate.shouldApply($name)) return@LaunchedEffect` as the " +
                    "first line of `LaunchedEffect($name) { ... }` in ARSceneView.kt (#2573). " +
                    "Window:\n$effectWindow",
                guardIdx >= 0,
            )
            assertTrue(
                "Expected `session.configure` inside `LaunchedEffect($name) { ... }`. Window:\n$effectWindow",
                configureIdx >= 0,
            )
            assertTrue(
                "The `${name}Gate` guard (idx=$guardIdx) must appear BEFORE `session.configure` " +
                    "(idx=$configureIdx) inside `LaunchedEffect($name) { ... }` — otherwise the " +
                    "mount-time run still reaches the session before being skipped. Window:\n$effectWindow",
                guardIdx in 0 until configureIdx,
            )
        }
    }
}

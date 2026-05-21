package io.github.sceneview.ar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the v4.3.0 acceptance criteria from [#1063](https://github.com/sceneview/sceneview/issues/1063)
 * that PR [#1069](https://github.com/sceneview/sceneview/pull/1069) deferred, plus the [#1101](https://github.com/sceneview/sceneview/issues/1101)
 * back-camera cleanup landed in the same patch.
 *
 * What this pins:
 * 1. `ARSceneView` exposes `fillLightNode: LightNode? = rememberFillLightNode(engine)` (acceptance #3).
 * 2. The default `Config.LightEstimationMode` set inside `ARSceneView`'s `session.configure` block
 *    is `ENVIRONMENTAL_HDR` AND comes BEFORE the user's `sessionConfigurationRef` callback so
 *    callers can override (acceptance #2).
 * 3. The deprecated `ARScene` alias forwards both new params.
 * 4. The fill-light SideEffect wires `fillLightNode` into `nodeManager` symmetrically with main.
 * 5. The baseline-cache is keyed on `mainLightNode` identity (so swapping light resets baseline).
 * 6. `onARFrame` only touches `mainLightNode` — `fillLightNode` never gets multiplied.
 * 7. Every back-camera AR demo dropped the post-#1088 `cameraExposure = -1.0f` workaround.
 * 8. `ARFaceDemo` no longer carries a negative `cameraExposure` value (#1179) — the prior
 *    `-1.5f` was an absolute linear gain (not signed EV) and rendered the scene fully black.
 *
 * Cheapest pin available — `ARSceneView` is a Composable, so reflection over its generated
 * synthetic parameters is brittle. Strings + regex it is.
 *
 * `ARSceneScope` parity is intentionally NOT pinned here: `fillLightNode` enters via
 * `nodeManager.addNode(...)`, NOT as a scope content child. The SideEffect pin (test #4) is the
 * right level of granularity.
 */
class ARCompletenessDefaultsTest {

    private val arSceneFile = File("src/main/java/io/github/sceneview/ar/ARScene.kt")
    private val demosDir = File("../samples/android-demo/src/main/java/io/github/sceneview/demo")

    private val arSceneSource: String by lazy {
        assertTrue(
            "Expected to find ${arSceneFile.absolutePath} — JVM test must run from arsceneview module root.",
            arSceneFile.exists()
        )
        arSceneFile.readText()
    }

    @Test
    fun `ARSceneView exposes fillLightNode parameter with rememberFillLightNode default`() {
        val src = arSceneSource
        // Match across whitespace/newline — Kotlin formatters sometimes wrap the `=`.
        val pattern = Regex(
            """fillLightNode\s*:\s*LightNode\?\s*=\s*rememberFillLightNode\(\s*engine\s*\)"""
        )
        assertTrue(
            "ARSceneView must expose `fillLightNode: LightNode? = rememberFillLightNode(engine)` " +
                "(#1063 acceptance). Source did not match $pattern.",
            pattern.containsMatchIn(src)
        )
    }

    @Test
    fun `ARSceneView defaults LightEstimationMode to ENVIRONMENTAL_HDR before user callback`() {
        val src = arSceneSource
        // Find the `session.configure { config -> ... }` block; the closing `}` is the one that
        // matches the open `{` after `config ->`.
        val openIdx = Regex("""session\.configure\s*\{\s*config\s*->""").find(src)?.range?.last
            ?: throw AssertionError("Could not find `session.configure { config -> ` in ARScene.kt")
        // We only need to scan past the user callback line. Window must accommodate the long-form
        // KDoc-style comments + later additions (e.g. #1732 flashMode wiring, #1766 typed
        // Config.*Mode block which adds ~20 lines).
        val window = src.substring(openIdx, (openIdx + 4000).coerceAtMost(src.length))
        val modeIdx = window.indexOf(
            "config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR"
        )
        val callbackIdx = Regex("""sessionConfigurationRef\.get\(\)\?""").find(window)?.range?.first ?: -1
        assertTrue(
            "Inside `session.configure { ... }` ARSceneView must set " +
                "`config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR` " +
                "(#1063 acceptance). Window:\n$window",
            modeIdx >= 0
        )
        assertTrue(
            "Inside `session.configure { ... }` ARSceneView must locate the user " +
                "`sessionConfigurationRef.get()?.invoke(...)` callback. Window:\n$window",
            callbackIdx >= 0
        )
        assertTrue(
            "`config.lightEstimationMode = ENVIRONMENTAL_HDR` (idx=$modeIdx) MUST come BEFORE " +
                "the user callback `sessionConfigurationRef.get()?` (idx=$callbackIdx) so the " +
                "default can still be overridden. Window:\n$window",
            modeIdx < callbackIdx
        )
    }

    @Test
    fun `ARScene deprecated alias forwards fillLightNode`() {
        val src = arSceneSource
        // The deprecated `fun ARScene(...)` body forwards every param to ARSceneView.
        // Make sure both the signature AND the call-site mention fillLightNode.
        val arSceneFn = Regex(
            """@Deprecated\("Use ARSceneView instead".*?fun ARScene\(.*?\)\s*=\s*ARSceneView\(.*?\)""",
            RegexOption.DOT_MATCHES_ALL
        ).find(src)?.value
        requireNotNull(arSceneFn) {
            "Could not find the deprecated `fun ARScene(...)` alias in ARScene.kt."
        }
        assertTrue(
            "Deprecated `ARScene` alias must accept `fillLightNode: LightNode? = rememberFillLightNode(engine)`.",
            arSceneFn.contains("fillLightNode: LightNode? = rememberFillLightNode(engine)")
        )
        assertTrue(
            "Deprecated `ARScene` alias must forward `fillLightNode = fillLightNode` to ARSceneView.",
            arSceneFn.contains("fillLightNode = fillLightNode")
        )
    }

    @Test
    fun `fillLightNode DisposableEffect attaches and detaches via nodeManager`() {
        val src = arSceneSource
        // Same pattern PR #1131 introduced on the 3D `Scene.kt`: `DisposableEffect(fillLightNode)`
        // adds on (re-)key, `onDispose` removes on disposal/key-change. Pre-#1131 used a
        // `SideEffect` + `prevFillLightRef` which leaked lights in a shared `rememberScene`.
        // Pin the exact pattern so a regression to SideEffect immediately fails.
        val disposableBlock = Regex(
            """DisposableEffect\(fillLightNode\)\s*\{\s*fillLightNode\?\.let\s*\{\s*nodeManager\.addNode\(it\)\s*\}\s*onDispose\s*\{\s*fillLightNode\?\.let\s*\{\s*nodeManager\.removeNode\(it\)\s*\}\s*\}\s*\}"""
        )
        assertTrue(
            "ARSceneView's fill-light wire-up MUST be a `DisposableEffect(fillLightNode) { add ; onDispose { remove } }` " +
                "block (matches 3D `Scene.kt` post-#1131). Source did not match $disposableBlock.",
            disposableBlock.containsMatchIn(src)
        )
    }

    @Test
    fun `baseline mainLight cache is keyed on mainLightNode identity`() {
        val src = arSceneSource
        // Keyed cache lets the #1017 reactive-LightSlot pattern swap mainLightNode and reset
        // the baseline to the new light's defaults rather than re-using stale values from the
        // previous light. Regressing this re-introduces the exponential decay bug (#1062).
        assertTrue(
            "`baselineMainLightColorRef` must be keyed on `mainLightNode` (remember(mainLightNode)).",
            Regex("""baselineMainLightColorRef\s*=\s*remember\(\s*mainLightNode\s*\)""")
                .containsMatchIn(src)
        )
        assertTrue(
            "`baselineMainLightIntensityRef` must be keyed on `mainLightNode` (remember(mainLightNode)).",
            Regex("""baselineMainLightIntensityRef\s*=\s*remember\(\s*mainLightNode\s*\)""")
                .containsMatchIn(src)
        )
    }

    @Test
    fun `onARFrame only mutates mainLightNode color and intensity`() {
        val src = arSceneSource
        // Slice the `onARFrame` body (private fun starts after the composable). We only want to
        // assert that `mainLightNode?.let { light -> light.color = ... ; light.intensity = ... }`
        // is present, AND that `fillLightNode` is NEVER referenced inside this private fun.
        val onArFrame = Regex("""private fun onARFrame\(.*?\)\s*\{.*?(?=\n\}\n\n|\nprivate fun|\nfun )""", RegexOption.DOT_MATCHES_ALL)
            .find(src)?.value
            ?: throw AssertionError("Could not find `private fun onARFrame(...)` body in ARScene.kt")
        assertTrue(
            "onARFrame must drive the estimator into `mainLightNode?.let { light -> ... }`.",
            onArFrame.contains("mainLightNode?.let { light")
        )
        assertFalse(
            "onARFrame MUST NOT reference `fillLightNode` — the fill light is a static baseline " +
                "and must NOT be multiplied by ARCore estimates (would re-introduce a #1062-style " +
                "drift on the fill light too).",
            onArFrame.contains("fillLightNode")
        )
    }

    @Test
    fun `back-camera demos no longer pass the cameraExposure dash-1f workaround`() {
        // ARFaceDemo is the front-camera exception — light estimation forced DISABLED there,
        // workaround is for the selfie AE bias and stays until a per-facing strategy lands.
        // Reviewer #5 B2: include ARCloudAnchorDemo / ARImageDemo / ARRerunDemo so any future
        // contributor who slips the workaround back into one of those gets caught.
        val backCamDemos = listOf(
            "demos/ARCloudAnchorDemo.kt",
            "demos/ARDepthOcclusionDemo.kt",
            "demos/ARImageDemo.kt",
            "demos/ARImageStabilizationDemo.kt",
            "demos/ARInstantPlacementDemo.kt",
            "demos/ARPlacementDemo.kt",
            "demos/ARPoseDemo.kt",
            "demos/ARRecordPlaybackDemo.kt",
            "demos/ARRerunDemo.kt",
            "demos/ARRooftopAnchorDemo.kt",
            "demos/ARStreetscapeDemo.kt",
            "demos/ARTerrainAnchorDemo.kt",
            "demos/OrbitalARDemo.kt",
            "ui/ArViewTab.kt",
        )
        // Reviewer #5 B1: a silent no-op if the working dir is wrong defeats the regression
        // pin. Count files actually inspected; require at least one to land an assertion.
        val workaround = Regex("""cameraExposure\s*=\s*-1\.0f""")
        var checked = 0
        backCamDemos.forEach { path ->
            val f = File(demosDir, path)
            if (!f.exists()) return@forEach
            checked++
            assertFalse(
                "$path still carries `cameraExposure = -1.0f` workaround. " +
                    "Drop the line — the post-#1088 EV11.6 default no longer needs it (#1101).",
                workaround.containsMatchIn(f.readText())
            )
        }
        assertTrue(
            "Whitelist regression test inspected 0 files — `demosDir`=$demosDir likely doesn't " +
                "resolve from this working dir. Without ≥1 file, the test silently no-ops " +
                "(reviewer #5 B1).",
            checked > 0
        )
    }

    @Test
    fun `ARFaceDemo no longer passes a negative cameraExposure value`() {
        // #1179 — the prior `cameraExposure = -1.5f` (intended as "negative EV bias")
        // rendered the entire scene fully black on Pixel 9 v4.3.0 production. Filament's
        // single-arg `setExposure(Float)` is an absolute LINEAR scaling (1.0 = ISO 100),
        // NOT a signed EV-stop bias as the prior KDoc misleadingly hinted. Any negative
        // value clamps the framebuffer to zero.
        //
        // ARSession force-DISABLES light estimation for front-camera sessions, so the new
        // `ARDefaultCameraNode` defaults (f/12 1/200 ISO 200 ≈ EV 11.6, after #1088) +
        // 10k+3k lux main+fill lights produce a correctly exposed selfie preview on every
        // device tested through Pixel 9. No per-demo override is needed.
        //
        // This test pins the FIX: ARFaceDemo MUST NOT carry a negative `cameraExposure`
        // value. Future grep-and-paste regressions get caught here.
        val arFaceDemo = File(demosDir, "demos/ARFaceDemo.kt")
        if (!arFaceDemo.exists()) return // shard without samples — fall back gracefully
        // Strip Kotlin line comments before scanning — the in-source rationale block
        // intentionally cites the prior `cameraExposure = -1.5f` value verbatim so the
        // bug history stays grep-able. Without this filter, the regex would match its
        // own warning comment and the test would self-fail.
        val src = arFaceDemo.readText()
            .lineSequence()
            .map { it.substringBefore("//") }
            .joinToString("\n")
        val negativeExposure = Regex("""cameraExposure\s*=\s*-\d""")
        assertFalse(
            "ARFaceDemo carries a negative `cameraExposure` value again. The single-arg " +
                "`setExposure(Float)` is a linear gain (not signed EV) — negative values " +
                "produce a fully-black framebuffer (#1179). Drop the override; the v4.3+ " +
                "EV11.6 default is correct for front-camera sessions.",
            negativeExposure.containsMatchIn(src)
        )
    }
}

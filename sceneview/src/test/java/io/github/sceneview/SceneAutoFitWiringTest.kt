package io.github.sceneview

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.full.functions
import kotlin.reflect.jvm.kotlinFunction

/**
 * Regression pin for **#1595**: the #1439 auto-fit camera-framing API
 * ([SceneAutoFitState], [fitDistanceForBounds], [CameraNode.frameToContent]) must be *reachable* —
 * wired into the public `SceneView` composable via an `autoFitContent` parameter — and not ship as
 * dead, caller-less public API.
 *
 * Before the #1595 fix, `CameraFraming.kt` exported the whole auto-fit surface but no
 * `autoFitContent` parameter existed on `SceneView` / `Scene` and nothing in `sceneview/src/main`
 * called [SceneAutoFitState.maybeFit] — the feature was a silent no-op. This test fails if that
 * regression returns: it reads the Kotlin `@Metadata` of the compiled `Scene.kt` facade (JVM
 * bytecode does not retain parameter names, but Kotlin reflection does) and asserts both
 * composables declare an `autoFitContent` parameter.
 */
class SceneAutoFitWiringTest {

    /** Names of every value parameter of the named top-level function in the `Scene.kt` facade. */
    private fun facadeFunctionParameterNames(functionName: String): Set<String> {
        val facade = Class.forName("io.github.sceneview.SceneKt")
        val kotlinFunctions = facade.declaredMethods
            .filter { it.name == functionName }
            .mapNotNull { it.kotlinFunction }
        assertTrue(
            "the `$functionName` composable must exist in the Scene.kt facade",
            kotlinFunctions.isNotEmpty()
        )
        return kotlinFunctions.flatMap { fn -> fn.parameters.mapNotNull { it.name } }.toSet()
    }

    @Test
    fun sceneViewComposableDeclaresAutoFitContentParameter() {
        assertTrue(
            "#1595: the SceneView composable must declare an `autoFitContent` parameter so the " +
                "#1439 auto-fit framing API is reachable — not dead caller-less public API",
            "autoFitContent" in facadeFunctionParameterNames("SceneView")
        )
    }

    @Test
    fun deprecatedSceneAliasAlsoForwardsAutoFitContent() {
        assertTrue(
            "#1595: the deprecated Scene alias must forward `autoFitContent` so callers on the " +
                "old name keep parity with SceneView",
            "autoFitContent" in facadeFunctionParameterNames("Scene")
        )
    }

    /** The auto-fit holder class must expose a callable `maybeFit` — the frame-loop entry point. */
    @Test
    fun sceneAutoFitStateExposesMaybeFitEntryPoint() {
        val hasMaybeFit = SceneAutoFitState::class.functions.any { it.name == "maybeFit" }
        assertTrue(
            "#1595: SceneAutoFitState must expose `maybeFit` — the symbol SceneView's frame loop " +
                "calls to drive the autoFitContent pass",
            hasMaybeFit
        )
    }

    /**
     * The auto-fit state holder must run the diagonal-stability gate, not a dead first-frame
     * latch — a freshly-constructed [SceneAutoFitState] starts un-latched and reports a `0`
     * fit distance, exactly like the gate's armed state.
     */
    @Test
    fun freshAutoFitStateIsArmedAndNotYetFitted() {
        val state = SceneAutoFitState()
        assertTrue("a fresh SceneAutoFitState must not have fitted yet", !state.didFit)
        assertTrue("a fresh SceneAutoFitState must report a zero fit distance", state.fitDistance == 0f)
    }
}

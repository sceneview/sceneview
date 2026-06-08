package io.github.sceneview.environment

import androidx.compose.runtime.DisposableEffectResult
import androidx.compose.runtime.DisposableEffectScope
import io.github.sceneview.loaders.EnvironmentLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

/**
 * Regression contract for issue [#2458](https://github.com/sceneview/sceneview/issues/2458)
 * — "rememberHDREnvironment/rememberKTXEnvironment leak the previous Environment on key change".
 *
 * Both factories build a Filament [Environment] (IndirectLight + Skybox, GPU-resident) via
 * `produceState`. `produceState` only cancels the producer coroutine on a key (asset-path) change —
 * it **never destroys the previously produced value**. So before the fix a key swap left the old
 * [Environment] in `EnvironmentLoader.environments`, GPU-resident until the whole loader was torn
 * down. The sibling `rememberEnvironment(loader, key = …)` on the *same* loader already disposes on
 * key change via `DisposableEffect { onDispose { loader.destroyEnvironment(it) } }` — the bug was a
 * clean asymmetry, not a missing-destroy-at-teardown.
 *
 * The fix keys a `DisposableEffect` on the produced [Environment] in each factory, so a new value
 * (key swap) and leave-composition both fire `onDispose` for the previous environment. `onDispose`
 * runs on the composition (main) thread, satisfying the Filament JNI threading contract.
 *
 * Exercising the live path needs a real Filament `Engine` + GPU (the loader constructor eagerly
 * builds an `IBLPrefilter`) and a Compose runtime, so — matching the existing
 * `EnvironmentLoaderIndirectLightApplyContractTest` / `LoaderCoroutineScopeContractTest`
 * convention — this pins the **compiled shape** of the fix instead: each factory must emit the
 * synthetic `DisposableEffect` disposal lambda that closes over the [Environment] and the
 * [EnvironmentLoader], and the loader must expose the `destroyEnvironment` call that lambda targets.
 * A revert to a bare `produceState` (no per-key dispose) deletes the synthetic lambda and fails.
 */
class EnvironmentPresetDisposeContractTest {

    private val presetsClass: Class<*> by lazy {
        Class.forName("io.github.sceneview.environment.EnvironmentPresetsKt")
    }

    @Test
    fun `rememberHDREnvironment emits a per-key Environment disposal effect`() {
        assertHasDisposalLambda("rememberHDREnvironment")
    }

    @Test
    fun `rememberKTXEnvironment emits a per-key Environment disposal effect`() {
        assertHasDisposalLambda("rememberKTXEnvironment")
    }

    @Test
    fun `EnvironmentLoader exposes the destroyEnvironment call the disposal effect targets`() {
        // The disposal lambda calls `environmentLoader.destroyEnvironment(env)` — the exact call
        // the sibling `rememberEnvironment(key = …)` already uses on the same loader. Pin its
        // single-Environment signature so a rename/removal can't silently strand the factory fix.
        val destroy = EnvironmentLoader::class.java.declaredMethods.firstOrNull {
            it.name == "destroyEnvironment" &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == Environment::class.java
        }
        assertNotNull(
            "EnvironmentLoader must expose `destroyEnvironment(Environment)` — the call the " +
                "rememberHDREnvironment/rememberKTXEnvironment disposal effect targets (#2458).",
            destroy
        )
    }

    @Test
    fun `both factories are still public Composable producers`() {
        // Sanity: the fix must not change the public surface — both factories stay public and keep
        // their Composer parameter (i.e. they remain @Composable).
        listOf("rememberHDREnvironment", "rememberKTXEnvironment").forEach { name ->
            val factory = presetsClass.declaredMethods.firstOrNull { it.name == name }
            assertNotNull("Missing public factory $name (#2458)", factory)
            assertTrue(
                "$name must remain a @Composable producer (Composer parameter present)",
                factory!!.parameterTypes.any { it.name == "androidx.compose.runtime.Composer" }
            )
        }
    }

    /**
     * Asserts [factoryName] emits the synthetic `DisposableEffect` disposal lambda the fix adds:
     * a `private static` method returning [DisposableEffectResult] that closes over an
     * [Environment] and an [EnvironmentLoader] (and receives a [DisposableEffectScope]). Matched
     * structurally — by return + parameter types, not by the `$lambda$…` mangled name — so it
     * stays robust across Kotlin compiler versions.
     */
    private fun assertHasDisposalLambda(factoryName: String) {
        val candidates = presetsClass.declaredMethods.filter { it.name.startsWith(factoryName) }
        val disposalLambda = candidates.firstOrNull { it.isEnvironmentDisposalLambda() }
        assertNotNull(
            "$factoryName must emit a DisposableEffect disposal lambda that destroys the " +
                "previously produced Environment on key change (#2458). `produceState` alone " +
                "never disposes the prior value — match the sibling rememberEnvironment(key = …). " +
                "Synthetic methods seen: ${candidates.joinToString { it.signature() }}",
            disposalLambda
        )
    }

    private fun Method.isEnvironmentDisposalLambda(): Boolean {
        if (returnType != DisposableEffectResult::class.java) return false
        val params = parameterTypes.toList()
        return Environment::class.java in params &&
            EnvironmentLoader::class.java in params &&
            DisposableEffectScope::class.java in params
    }

    private fun Method.signature(): String =
        "$name(${parameterTypes.joinToString { it.simpleName }}): ${returnType.simpleName}"

    @Test
    fun `the asymmetry is closed - both produceState factories now dispose like the remember sibling`() {
        // Umbrella assertion tying the two factories together: BOTH (not just one) must carry the
        // disposal lambda. The original bug shipped two leaking factories; a partial fix that wires
        // only one would still leak the other.
        val disposers = presetsClass.declaredMethods.count { it.isEnvironmentDisposalLambda() }
        assertEquals(
            "Exactly the two produceState factories (rememberHDREnvironment + " +
                "rememberKTXEnvironment) must each emit an Environment disposal effect (#2458).",
            2,
            disposers
        )
    }
}

package io.github.sceneview

import androidx.compose.runtime.DisposableEffectResult
import androidx.compose.runtime.DisposableEffectScope
import com.google.android.filament.gltfio.FilamentInstance
import io.github.sceneview.loaders.ModelLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

/**
 * Regression contract for issue [#2459](https://github.com/sceneview/sceneview/issues/2459)
 * — "rememberModelInstance leaks the previous Model into ModelLoader on key change".
 *
 * Both `rememberModelInstance` overloads build a [io.github.sceneview.model.ModelInstance] (a
 * `FilamentInstance` backed by a glTF [io.github.sceneview.model.Model] — Filament textures,
 * vertex/index buffers and materials, all GPU-resident) via `produceState`. `produceState` only
 * cancels the producer coroutine on a key (asset-path) change — it **never destroys the previously
 * produced value**. So before the fix a key swap left the old `Model` in `ModelLoader.models`,
 * GPU-resident until the **whole loader** was torn down (i.e. the entire `SceneView` left the
 * composition). [ModelLoader] does not dedupe by path — each `createModelInstance` appends a fresh
 * `Model` — so this is a genuine, unbounded leak, not a cache: the live Sketchfab gallery swap path
 * (`SketchfabModelViewerScreen`, re-keyed on `fileLocation`) grew GPU memory on every swap. This is
 * the same swap path #2400/#2424 fixed for *rendering*; this closes the *memory* side.
 *
 * The fix keys a `DisposableEffect` on the produced [io.github.sceneview.model.ModelInstance] in
 * each overload, so a new value (key swap) and leave-composition both fire `onDispose` for the
 * previous instance, calling `modelLoader.destroyModel(it.model)`. The disposal effect is declared
 * **before** any consuming `SceneScope.ModelNode` in the caller, so on a swap Compose runs the
 * node's `NodeLifecycle.onDispose` (which detaches the renderable entities from the Filament scene)
 * first and this `destroyModel` after — the renderables are off the scene before the `Model`'s GPU
 * buffers are freed, respecting #2424's render-loop ordering and avoiding a use-after-free.
 * `onDispose` runs on the composition (main) thread, satisfying the Filament JNI contract.
 *
 * Exercising the live path needs a real Filament `Engine` + GPU and a Compose runtime, so —
 * matching the sibling `EnvironmentPresetDisposeContractTest` (#2458) convention — this pins the
 * **compiled shape** of the fix instead: each overload must emit the synthetic `DisposableEffect`
 * disposal lambda that closes over the [FilamentInstance] and the [ModelLoader], and the loader
 * must expose the `destroyModel` call that lambda targets. A revert to a bare `produceState` (no
 * per-key dispose) deletes the synthetic lambda and fails.
 */
class ModelInstanceDisposeContractTest {

    // Top-level functions in SceneView.kt compile into this facade (`@file:JvmName("SceneKt")`).
    private val sceneClass: Class<*> by lazy {
        Class.forName("io.github.sceneview.SceneKt")
    }

    @Test
    fun `rememberModelInstance emits per-key ModelInstance disposal effects`() {
        val disposalLambda =
            sceneClass.declaredMethods.firstOrNull { it.isModelInstanceDisposalLambda() }
        assertNotNull(
            "rememberModelInstance must emit a DisposableEffect disposal lambda that destroys the " +
                "previously produced Model on key change (#2459). `produceState` alone never " +
                "disposes the prior value — and ModelLoader does not dedupe by path, so the old " +
                "Model stays GPU-resident until loader teardown. Synthetic methods seen: " +
                sceneClass.declaredMethods.filter {
                    it.name.contains("rememberModelInstance")
                }.joinToString { it.signature() },
            disposalLambda
        )
    }

    @Test
    fun `both rememberModelInstance overloads dispose - the asymmetry with the remember siblings is closed`() {
        // The original bug shipped two leaking overloads (asset-path + URL/URI). A partial fix
        // wiring only one would still leak the other. The URL overload's no-scheme fast path
        // delegates to the asset overload (which owns its own disposal), so each overload still
        // contributes exactly one disposal lambda → two in total.
        val disposers = sceneClass.declaredMethods.count { it.isModelInstanceDisposalLambda() }
        assertEquals(
            "Exactly the two rememberModelInstance overloads (asset-path + URL/URI) must each emit " +
                "a Model disposal effect (#2459).",
            2,
            disposers
        )
    }

    @Test
    fun `ModelLoader exposes the destroyModel call the disposal effect targets`() {
        // The disposal lambda calls `modelLoader.destroyModel(instance.model)` — the only path that
        // both frees the asset's GPU buffers and removes it from `ModelLoader.models`. Pin its
        // single-Model signature so a rename/removal can't silently strand the factory fix.
        val destroy = ModelLoader::class.java.declaredMethods.firstOrNull {
            it.name == "destroyModel" &&
                it.parameterCount == 1 &&
                // Model is a typealias for FilamentAsset (erased to FilamentAsset on the JVM).
                it.parameterTypes[0] == com.google.android.filament.gltfio.FilamentAsset::class.java
        }
        assertNotNull(
            "ModelLoader must expose `destroyModel(Model)` — the call the rememberModelInstance " +
                "disposal effect targets (#2459).",
            destroy
        )
    }

    @Test
    fun `both rememberModelInstance overloads are still public Composable producers`() {
        // Sanity: the fix must not change the public surface — both overloads stay public and keep
        // their Composer parameter (i.e. they remain @Composable). Identified by the distinct
        // ModelLoader + (String | resourceResolver function) parameter shapes.
        val overloads = sceneClass.declaredMethods.filter {
            it.name == "rememberModelInstance" &&
                it.parameterTypes.any { p -> p == ModelLoader::class.java }
        }
        assertTrue(
            "Expected the two public rememberModelInstance overloads (#2459); found " +
                overloads.joinToString { it.signature() },
            overloads.size >= 2
        )
        overloads.forEach { factory ->
            assertTrue(
                "${factory.signature()} must remain a @Composable producer (Composer parameter present)",
                factory.parameterTypes.any { it.name == "androidx.compose.runtime.Composer" }
            )
        }
    }

    /**
     * Matches the synthetic `DisposableEffect` disposal lambda the fix adds: a method returning
     * [DisposableEffectResult] that closes over a [FilamentInstance] (the produced
     * `ModelInstance`) and a [ModelLoader], and receives a [DisposableEffectScope]. Matched
     * structurally — by return + parameter types, not by the `$lambda$…` mangled name — so it stays
     * robust across Kotlin compiler versions.
     */
    private fun Method.isModelInstanceDisposalLambda(): Boolean {
        if (returnType != DisposableEffectResult::class.java) return false
        val params = parameterTypes.toList()
        return FilamentInstance::class.java in params &&
            ModelLoader::class.java in params &&
            DisposableEffectScope::class.java in params
    }

    private fun Method.signature(): String =
        "$name(${parameterTypes.joinToString { it.simpleName }}): ${returnType.simpleName}"
}

package io.github.sceneview.demo.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.model.model
import java.io.File

/**
 * Load a [ModelInstance] from a nullable local [File], returning `null` until the file is
 * ready — and destroy the previous `Model` when the file changes or the caller leaves
 * composition.
 *
 * The one copy of a pattern that used to exist three times (Materials, Model Viewer's
 * Multi-Model + Gallery sections, Orbital AR) — of which only one had the disposal half
 * (#2945). The Multi-Model section leaked four instances per bundle-chip switch (#2954).
 *
 * ### Why not `rememberModelInstance(modelLoader, String)`
 *
 * The Sketchfab resolver always hands back a real on-disk [File] (streamed GLB or staged
 * bundled fallback), so the model must be loaded through [ModelLoader.loadModelInstance],
 * which understands `file://` URIs. The two-argument `rememberModelInstance` call is **not**
 * usable here: Kotlin overload resolution binds it to the asset-path overload (the one
 * without a defaulted `resourceResolver`), which feeds the `file://` string straight to
 * `AssetManager.open` — that throws, the instance stays `null`, and the demo hangs forever
 * on its loading scrim (#1422, #2302, #2306). Loading via `produceState` +
 * `loadModelInstance` keeps the Filament JNI work on the loader's own Main-thread hop.
 *
 * ### Slot stability
 *
 * The null check lives **inside** `produceState`, never as an early `return null` above it:
 * a null file means "nothing streamed is selected" (or "download in flight"), and an early
 * return would add / remove the `produceState` slot on each switch, which is how #1464 lost
 * its invalidations. Callers may therefore call this unconditionally.
 *
 * ### Disposal — what is guaranteed and what is not
 *
 * `produceState` cancels its producer on a key change but never destroys the model it
 * already produced, so without the [DisposableEffect] every switch left the previous
 * streamed `Model` GPU-resident in `ModelLoader.models` until the section's engine was torn
 * down (#2459). Keying the effect on the produced value fires `onDispose` for the
 * **previous** instance on a swap and on leave-composition, mirroring the library's own
 * `rememberModelInstance` contract. `onDispose` runs on the composition (main) thread,
 * satisfying the Filament JNI contract.
 *
 * The order relative to the consuming `ModelNode`'s own teardown is **not** guaranteed, and
 * this helper does not rely on it. Compose does forget effects in reverse registration
 * order, but only within one composition's leaving list — and the node lives in a different
 * one: the demos declare it inside `DemoScaffold`'s `scene` slot, which Material3's
 * `Scaffold` runs through a `SubcomposeLayout`. Across that boundary the node may well be
 * detached *after* the model is destroyed. That is safe either way: `Node.destroy()` only
 * touches entity ids and `ModelLoader.destroyModel` is `runCatching`-guarded, so the worst
 * case is a no-op detach of entities Filament has already dropped. Measured on the QA
 * emulator (#2954): five consecutive Materials chip swaps across three distinct staged
 * files, no black viewport, no crash.
 *
 * ### What this does not cover
 *
 * Only a model that *reached* `value =` is ever disposed. If the key changes while a load is
 * in flight, `produceState` cancels the producer after `ModelLoader.loadModel` has already
 * added the `Model` to `ModelLoader.models` but before it is assigned here — that `Model`
 * is unreachable and stays resident until the loader is torn down. The library helper has
 * the same hole; the fix belongs in `ModelLoader` (load into a local, register on success
 * only), not in a demo. So the leak this closes is "one `Model` per **completed** switch".
 */
@Composable
fun rememberFileModelInstance(
    modelLoader: ModelLoader,
    file: File?,
): ModelInstance? {
    val instance = produceState<ModelInstance?>(
        initialValue = null,
        key1 = modelLoader,
        key2 = file?.absolutePath,
    ) {
        value = file?.let {
            runCatching { modelLoader.loadModelInstance("file://${it.absolutePath}") }.getOrNull()
        }
    }.value
    DisposableEffect(instance) {
        onDispose { instance?.let { modelLoader.destroyModel(it.model) } }
    }
    return instance
}

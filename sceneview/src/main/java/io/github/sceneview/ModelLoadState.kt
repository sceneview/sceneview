package io.github.sceneview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.github.sceneview.loaders.ModelLoadProgress
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.loaders.UnsupportedGltfExtensionException
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.model.model
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Where a model load started by [rememberModelLoadState] stands: what a progress bar, an error
 * message and the model itself need, in one value.
 */
sealed interface ModelLoadState {

    /** Reading, creating or texturing the model: [progress] says which, and how far. */
    data class Loading(val progress: ModelLoadProgress) : ModelLoadState

    /** Loaded, every texture final. The model is owned by [rememberModelLoadState]. */
    data class Ready(val instance: ModelInstance) : ModelLoadState

    /**
     * The load failed: [error] is an [UnsupportedGltfExtensionException] for a model that requires
     * a glTF extension Filament cannot load, an `IllegalArgumentException` for a file that cannot
     * be read or parsed, an [OutOfMemoryError] for a model too large for the Java heap.
     */
    data class Failed(val error: Throwable) : ModelLoadState
}

/**
 * Loads a model with [ModelLoader.awaitModelInstance] and remembers where it stands, for a UI that
 * shows the progress of a large model (a generated one: tens of MB, 4K textures) and says why one
 * did not load, where [rememberModelInstance] only gives `null` until the instance exists.
 *
 * ```kotlin
 * when (val state = rememberModelLoadState(modelLoader, "https://example.com/generated.glb")) {
 *     is ModelLoadState.Loading -> LinearProgressIndicator(progress = { state.progress.fraction })
 *     is ModelLoadState.Failed -> Text("Could not load the model: ${state.error.message}")
 *     is ModelLoadState.Ready -> SceneView(...) { ModelNode(modelInstance = state.instance) }
 * }
 * ```
 *
 * The state is [ModelLoadState.Ready] once every texture is final, so the model never shows
 * untextured. It goes back to [ModelLoadState.Loading] when [fileLocation] or [modelLoader]
 * changes. The model is owned here, as with [rememberModelInstance]: it is destroyed when
 * [fileLocation] changes and when this leaves the composition, and a load cancelled before it is
 * ready destroys what it created.
 *
 * @param fileLocation an asset path (`models/helmet.glb`), a `file://`, `content://` or
 * `android.resource://` URI, or an `http(s)` URL.
 */
@Composable
fun rememberModelLoadState(
    modelLoader: ModelLoader,
    fileLocation: String,
    resourceResolver: (resourceFileName: String) -> String = {
        ModelLoader.getFolderPath(fileLocation, it)
    },
): ModelLoadState {
    val state = remember(modelLoader, fileLocation) {
        mutableStateOf<ModelLoadState>(ModelLoadState.Loading(ModelLoadProgress(ModelLoadProgress.Stage.Reading, 0f)))
    }
    val scope = rememberCoroutineScope()
    // One effect both runs the load and owns its result. With `produceState` plus a
    // `DisposableEffect` keyed on the produced instance (as in `rememberModelInstance`), a model that
    // became ready just before this left the composition was never seen by the effect, and leaked.
    // Everything here runs on the main thread, so the dispose below either finds the model ready
    // and destroys it, or cancels a load that has not returned, which destroys what it created.
    DisposableEffect(modelLoader, fileLocation) {
        val load = scope.launch {
            state.value = try {
                ModelLoadState.Ready(
                    modelLoader.awaitModelInstance(fileLocation, resourceResolver) {
                        state.value = ModelLoadState.Loading(it)
                    },
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                ModelLoadState.Failed(error)
            } catch (error: OutOfMemoryError) {
                // A generated model too large for the heap: a failed load, not a crashed app.
                ModelLoadState.Failed(error)
            }
        }
        onDispose {
            load.cancel()
            (state.value as? ModelLoadState.Ready)?.let { modelLoader.destroyModel(it.instance.model) }
        }
    }
    return state.value
}

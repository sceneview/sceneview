package io.github.sceneview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import io.github.sceneview.loaders.ModelLoadProgress
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.loaders.UnsupportedGltfExtensionException
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.model.model
import kotlinx.coroutines.CancellationException

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
     * be read or parsed.
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
    val state = produceState<ModelLoadState>(
        initialValue = ModelLoadState.Loading(ModelLoadProgress(ModelLoadProgress.Stage.Reading, 0f)),
        key1 = modelLoader,
        key2 = fileLocation,
    ) {
        value = ModelLoadState.Loading(ModelLoadProgress(ModelLoadProgress.Stage.Reading, 0f))
        value = try {
            ModelLoadState.Ready(
                modelLoader.awaitModelInstance(fileLocation, resourceResolver) {
                    value = ModelLoadState.Loading(it)
                },
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            ModelLoadState.Failed(error)
        }
    }.value
    // As in `rememberModelInstance`: `produceState` never destroys what it produced, so the
    // model is destroyed here, on a key change and on leaving the composition (main thread).
    val instance = (state as? ModelLoadState.Ready)?.instance
    DisposableEffect(instance) {
        onDispose { instance?.let { modelLoader.destroyModel(it.model) } }
    }
    return state
}

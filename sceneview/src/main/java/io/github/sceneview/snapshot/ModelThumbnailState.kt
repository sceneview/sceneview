package io.github.sceneview.snapshot

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.sceneview.ExperimentalSceneViewApi

/**
 * Where a thumbnail asked with [rememberModelThumbnail] stands: [Loading], then [Ready] or
 * [Failed] for good — a failed thumbnail is not retried until its file, size or options change.
 */
@ExperimentalSceneViewApi
sealed interface ModelThumbnailState {

    /** Queued or rendering: draw a placeholder. */
    data object Loading : ModelThumbnailState

    /** The thumbnail, on a transparent background. */
    data class Ready(val bitmap: ImageBitmap) : ModelThumbnailState

    /**
     * No thumbnail: the file could not be loaded, it drew nothing (an empty model, a bounding box
     * that cannot be framed, positions quantized with `KHR_mesh_quantization`), the render timed
     * out, or the snapshot was destroyed. [reason] says which, in English, for logs; it is also
     * logged under the `ModelSnapshot` tag.
     */
    data class Failed(val reason: String) : ModelThumbnailState

    /** The thumbnail when [Ready], else `null`. */
    fun bitmapOrNull(): ImageBitmap? = (this as? Ready)?.bitmap
}

/** What one render request came to, before it is cached or handed out. */
internal sealed interface SnapshotResult {

    /**
     * A drawn image. [complete] is `false` when the model's textures were still decoding after the
     * texture timeout, or when its glTF could not be read to check for quantized positions: the
     * image is handed out, never cached, so a later request renders it again.
     */
    class Rendered(val bitmap: Bitmap, val complete: Boolean) : SnapshotResult

    class Failed(val reason: String) : SnapshotResult
}

@OptIn(ExperimentalSceneViewApi::class)
internal fun SnapshotResult.toThumbnailState(): ModelThumbnailState = when (this) {
    is SnapshotResult.Rendered -> ModelThumbnailState.Ready(bitmap.asImageBitmap())
    is SnapshotResult.Failed -> ModelThumbnailState.Failed(reason)
}

/**
 * The state of one thumbnail request, keyed on [owner] and [key]: a key change starts from
 * [cached] (or [ModelThumbnailState.Loading]) in the very composition that changes it — the
 * previous key's image is never shown for the new one — and runs [load] only when nothing was
 * cached. A load superseded by a key change is cancelled and never writes.
 */
@OptIn(ExperimentalSceneViewApi::class)
@Composable
internal fun rememberThumbnailState(
    owner: Any,
    key: Any,
    cached: () -> ModelThumbnailState?,
    load: suspend () -> ModelThumbnailState,
): ModelThumbnailState {
    val state = remember(owner, key) { mutableStateOf(cached() ?: ModelThumbnailState.Loading) }
    LaunchedEffect(state) {
        if (state.value == ModelThumbnailState.Loading) state.value = load()
    }
    return state.value
}

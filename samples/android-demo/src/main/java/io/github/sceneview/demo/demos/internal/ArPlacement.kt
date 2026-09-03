package io.github.sceneview.demo.demos.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.google.ar.core.TrackingState
import kotlinx.coroutines.delay

/**
 * Shared AR-placement behaviour for the tap-to-place demos.
 *
 * There is one of those now — [io.github.sceneview.demo.demos.ARPlacementDemo], reached
 * through the shared `common/placement/` session — because #3405 folded
 * `ar-instant-placement` into it. These constants survive the fold because each encodes a
 * measured on-device fact, not because two call sites needed to agree.
 *
 * Two on-device QA findings from the 2026-05-16 Pixel 9 pass
 * ([#1435](https://github.com/sceneview/sceneview/issues/1435)) are fixed here so both
 * demos share one source of truth:
 *
 * 1. **Placed content vanished on plane loss.** An `AnchorNode` defaults to
 *    `visibleTrackingStates = setOf(TrackingState.TRACKING)`, so the moment ARCore
 *    demotes the anchor to [TrackingState.PAUSED] (camera looks away, plane drops out
 *    of view) the model disappears entirely. ARCore keeps the *last known pose* for a
 *    `PAUSED` anchor — and `AnchorNode.update()` already freezes the node pose while
 *    not `TRACKING` — so the correct UX is to keep rendering the model at its frozen
 *    pose through transient loss, and only hide it when the anchor is permanently
 *    [TrackingState.STOPPED]. See [ANCHORED_VISIBLE_STATES].
 *
 * 2. **Models flashed black before textures appeared.** `rememberModelInstance`
 *    returns as soon as the glTF hierarchy is parsed, but Filament's `ResourceLoader`
 *    uploads textures asynchronously over the next few frames (`asyncUpdateLoad()`),
 *    so the freshly-placed model renders untextured (black) until the upload lands.
 *    [rememberTexturesSettled] gates `ModelNode.isVisible` for a short settle window
 *    after the instance loads, so the model only appears once it is textured.
 */
internal object ArPlacement {

    /**
     * The settle window, in milliseconds, that [rememberTexturesSettled] waits after a
     * [com.google.android.filament.gltfio.ModelInstance] becomes available before the
     * model is made visible. Long enough for Filament's `ResourceLoader.asyncUpdateLoad()`
     * to finalize texture uploads across a handful of 60 fps frames, short enough that
     * the placement still feels instant.
     */
    const val TEXTURE_SETTLE_MS: Long = 120L

    /**
     * Tracking states for which an anchored placed model should stay rendered.
     *
     * Includes [TrackingState.PAUSED] so transient plane loss does not make placed
     * content vanish — the model holds its last known pose until ARCore either
     * re-acquires tracking or permanently [TrackingState.STOPPED]s the anchor.
     */
    val ANCHORED_VISIBLE_STATES: Set<TrackingState> =
        setOf(TrackingState.TRACKING, TrackingState.PAUSED)
}

/**
 * Returns `false` until [TEXTURE_SETTLE_MS][ArPlacement.TEXTURE_SETTLE_MS] has elapsed
 * since [ready] first turned `true`, then `true`. Use it to drive `ModelNode.isVisible`
 * so a placed model is shown only once Filament has uploaded its textures, avoiding the
 * black flash reported in [#1435](https://github.com/sceneview/sceneview/issues/1435).
 *
 * @param ready `true` once the model instance has been created (e.g. the non-null
 *              result of `rememberModelInstance`).
 */
@Composable
internal fun rememberTexturesSettled(ready: Boolean): Boolean {
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(ready) {
        if (ready) {
            delay(ArPlacement.TEXTURE_SETTLE_MS)
            settled = true
        } else {
            settled = false
        }
    }
    return ready && settled
}

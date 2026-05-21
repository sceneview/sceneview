package io.github.sceneview.audio

import io.github.sceneview.node.Node

/**
 * Where the virtual "ears" of a [SpatialAudioNode] sit in world space.
 *
 * Default ([Camera]) is what almost every interactive app wants: the listener tracks the
 * camera so audio reacts to the user's viewpoint.
 *
 * [Anchor] is reserved for phase 2 of #1900 (AR anchored audio); it is declared today so
 * the public API doesn't churn between phases. The phase-1 fallback logs a warning and
 * substitutes the camera at runtime — see [SpatialAudioNode] for the full behaviour table.
 */
sealed interface AudioListenerSource {

    /** Tracks the camera pose each frame. */
    object Camera : AudioListenerSource

    /**
     * Anchors the listener to a node (typically an `AnchorNode` in `arsceneview`).
     *
     * **Phase 1 (v4.12):** declared but not yet implemented — the engine logs a warning
     * (`SpatialAudio: anchor listener — phase 2 (#1900) — falling back to camera`) on
     * attach and uses [Camera] semantics. Wire this up for real in phase 2.
     *
     * Declared as `Node` rather than `AnchorNode` so this module stays free of an
     * `arsceneview` dependency.
     */
    data class Anchor(val node: Node) : AudioListenerSource
}

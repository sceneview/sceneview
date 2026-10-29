package io.github.sceneview.demo.demos.internal

/** Where a video read from the network stands, and so what its screen has to show. */
internal enum class StreamPhase {
    /** Asked for, nothing to show yet: the screen says so. */
    Loading,

    /** Prepared: the player can be handed to a `VideoNode`. */
    Ready,

    /** No network, no decoder, or the stream broke: the screen says so, nothing plays. */
    Failed,
}
